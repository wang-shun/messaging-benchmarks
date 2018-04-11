/*
 * Copyright (c) 2014, Oracle America, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of Oracle nor the names of its contributors may be used
 *    to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.aitusoftware.messaging;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import sun.misc.Unsafe;

@State(Scope.Benchmark)
@Measurement(iterations = 10)
@Warmup(iterations = 10)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
public class CompareAndSwapBenchmark {
    private static final VarHandle LONG_ARRAY_VIEW =
      MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static final Unsafe UNSAFE = getUnsafe();
    private final AtomicLong atomic = new AtomicLong();
    private final ByteBuffer heapBuffer = ByteBuffer.allocate(8192).alignedSlice(8);
    private final ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(8192).alignedSlice(8);
    private final long[] values = new long[] {0, 1, 2, 3, 4, 5, 6, 7};
    private final int valuesMask = values.length - 1;
    private final UnsafeBuffer agronaBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(4096));
    private int counter;

    @Setup(Level.Trial)
    public void setup() {
        atomic.set(values[0]);
        LONG_ARRAY_VIEW
          .compareAndExchange(nativeBuffer, 0, 0, values[0]);
        agronaBuffer.getAndSetLong(0, values[0]);
        counter = 1;
    }

    @Benchmark
    public long casLongAtomic() {
        long nextValue = values[counter & valuesMask];
        long previousValue = values[(counter -1) & valuesMask];
        final long witness = atomic.compareAndExchange(previousValue, nextValue);
        if(witness != previousValue) {
            throw new IllegalStateException("Counter: " + counter + ", next: " + nextValue +
              ", previous: " + previousValue + ", witness: " + witness);
        }
        counter++;
        return witness;
    }

    @Benchmark
    public long casLongNativeByteBuffer() {
        long nextValue = values[counter & valuesMask];
        long previousValue = values[(counter -1) & valuesMask];
        final long witness = (long) LONG_ARRAY_VIEW
          .compareAndExchange(nativeBuffer, 0, previousValue, nextValue);
        if(witness != previousValue) {
            throw new IllegalStateException("Counter: " + counter + ", next: " + nextValue +
              ", previous: " + previousValue + ", witness: " + witness);
        }
        counter++;
        return witness;
    }

    @Benchmark
    public long casLongUnsafeBuffer() {
        long nextValue = values[counter & valuesMask];
        long previousValue = values[(counter -1) & valuesMask];
        if (!agronaBuffer.compareAndSetLong(0, previousValue, nextValue)) {
            throw new IllegalStateException("Counter: " + counter + ", next: " + nextValue +
              ", previous: " + previousValue);

        }
        counter++;
        return nextValue;
    }

    @SuppressWarnings("restriction")
    private static Unsafe getUnsafe() {
        Field singleoneInstanceField = null;
        try {
            singleoneInstanceField = Unsafe.class.getDeclaredField("theUnsafe");
            singleoneInstanceField.setAccessible(true);
            return (Unsafe) singleoneInstanceField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot get unsafe", e);
        }
    }

}