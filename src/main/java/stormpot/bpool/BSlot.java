/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.bpool;

import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

class BSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  static final int LIVING = 1;
  static final int CLAIMED = 2;
  static final int TLR_CLAIMED = 3;
  static final int DEAD = 4;

  private final AtomicInteger state;

  private int get() {
    return state.get();
  }

  private void lazySet(int value) {
    state.lazySet(value);
  }

  private boolean compareAndSet(int expect, int update) {
    return state.compareAndSet(expect, update);
  }


  private final BlockingQueue<BSlot<T>> live;
  T obj;
  Exception poison;
  long created;
  long claims;
  long stamp;
  
  public BSlot(BlockingQueue<BSlot<T>> live) {
    this.live = live;
    this.state = new PaddedAtomicInteger(DEAD);
  }
  
  public void release(Poolable obj) {
    int slotState;
    do {
      slotState = get();
      // We loop here because TLR_CLAIMED slots can be concurrently changed
      // into normal CLAIMED slots.
    } while (!tryTransitionToLive(slotState));
    if (slotState == CLAIMED) {
      live.offer(this);
    }
  }

  private boolean tryTransitionToLive(int slotState) {
    if (slotState == TLR_CLAIMED) {
      return claimTlr2live();
    } else if (slotState == CLAIMED) {
      return claim2live();
    }
    throw new PoolException("Slot release from bad state: " + slotState);
  }
  
  public boolean claim2live() {
    lazySet(LIVING);
    return true;
  }

  public boolean claimTlr2live() {
    // TODO we cannot lazySet here because we need to know if the slot was
    // concurrently transitioned to an ordinary CLAIMED state
//    lazySet(LIVING);
//    return true;
    return compareAndSet(TLR_CLAIMED, LIVING);
  }
  
  public boolean live2claim() {
    return compareAndSet(LIVING, CLAIMED);
  }
  
  public boolean live2claimTlr() {
    return compareAndSet(LIVING, TLR_CLAIMED);
  }
  
  public boolean claimTlr2claim() {
    return compareAndSet(TLR_CLAIMED, CLAIMED);
  }
  
  public boolean claim2dead() {
    return compareAndSet(CLAIMED, DEAD);
  }

  // Never fails
  public void dead2live() {
    lazySet(LIVING);
  }
  
  public boolean live2dead() {
    return compareAndSet(LIVING, DEAD);
  }

  @Override
  public long getAgeMillis() {
    return System.currentTimeMillis() - created;
  }

  @Override
  public long getClaimCount() {
    return claims;
  }

  @Override
  public T getPoolable() {
    return obj;
  }

  public boolean isDead() {
    return get() == DEAD;
  }
  
  public int getState() {
    return get();
  }

  public void incrementClaims() {
    claims++;
  }

  // XorShift PRNG with a 2^128-1 period.
  private int x = System.identityHashCode(this);
  private int y = 938745813;
  private int z = 452465366;
  private int w = 1343246171;
  
  @Override
  public int randomInt() {
    int t=(x^(x<<15));
    x=y; y=z; z=w;
    return w=(w^(w>>>21))^(t^(t>>>4));
  }

  @Override
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }
}
