package com.aitusoftware.messaging.ipc;

import org.HdrHistogram.Histogram;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class UnsafeHarness
{
    private static final int MESSAGE_COUNT = Integer.getInteger("ipc.msgCount", 1 << 20);
    private static final long MAX_VALUE = TimeUnit.MILLISECONDS.toNanos(5L);
    private static final int BUFFER_SIZE = Integer.getInteger("ipc.bufferSize", MESSAGE_COUNT / 8);
    private static final int MESSAGE_SIZE = Integer.getInteger("ipc.msgSize", 256);
    private static final long DELAY_NS = Long.getLong("ipc.pub.delayNs", 0);
    private static final boolean SHOULD_DELAY = DELAY_NS != 0;

    private final UnsafeBufferTransport clientPublisher;
    private final UnsafeBufferTransport clientSubscriber;
    private final UnsafeBufferTransport serverPublisher;
    private final UnsafeBufferTransport serverSubscriber;
    private final UnsafeBuffer message;
    private final Histogram histogram = new Histogram(MAX_VALUE, 3);
    private final int sequenceOffset;
    private final Consumer<UnsafeBuffer> echoMessage = this::echoMessage;
    private final Consumer<UnsafeBuffer> receiveMessage = this::receiveMessage;
    private long sequence;
    private long messageCount;

    public static void main(String[] args) throws IOException
    {
        new UnsafeHarness(Paths.get("/dev/shm/ipc-in"),
                Paths.get("/dev/shm/ipc-out"), MESSAGE_SIZE).runLoop();
    }

    public UnsafeHarness(Path ipcFileIn, Path ipcFileOut, int messageSize) throws IOException
    {
        if (Files.exists(ipcFileIn))
        {
            Files.delete(ipcFileIn);
        }
        if (Files.exists(ipcFileOut))
        {
            Files.delete(ipcFileOut);
        }
        ByteBuffer message = ByteBuffer.allocateDirect(messageSize);
        for (int i = 0; i < messageSize; i++)
        {
            message.put(i, (byte) 7);
        }
        message.clear();
        this.message = new UnsafeBuffer(message);
        this.sequenceOffset = messageSize - 8;

        clientPublisher = new UnsafeBufferTransport(ipcFileIn, BUFFER_SIZE);
        clientSubscriber = new UnsafeBufferTransport(ipcFileOut, BUFFER_SIZE);
        serverPublisher = new UnsafeBufferTransport(ipcFileOut, BUFFER_SIZE);
        serverSubscriber = new UnsafeBufferTransport(ipcFileIn, BUFFER_SIZE);
    }

    private void echoLoop()
    {
        Thread.currentThread().setName("echo");
        Util.setCpu("echo", Util.ECHO_CPU);
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                serverSubscriber.poll(echoMessage);
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    private void echoMessage(UnsafeBuffer message)
    {
        serverPublisher.writeRecord(message);
    }

    private void runLoop()
    {
        ExecutorService executor = Executors.newCachedThreadPool();
        executor.submit(this::echoLoop);
        executor.submit(this::receiveLoop);
        Thread.currentThread().setName("harness");
        Util.setCpu("publish", Util.PUBLISHER_CPU);
        while (!Thread.currentThread().isInterrupted())
        {
            for (int i = 0; i < MESSAGE_COUNT; i++)
            {
                message.putLong(sequenceOffset, sequence++);
                final long publishNanos = System.nanoTime();
                message.putLong(0, publishNanos);
                try
                {
                    clientPublisher.writeRecord(message);
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                    return;
                }

                if (SHOULD_DELAY)
                {
                    final long waitUntil = publishNanos + DELAY_NS;
                    while (System.nanoTime() < waitUntil)
                    {
                        // spin
                    }
                }
            }

            final long spinUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (System.nanoTime() < spinUntil)
            {
                // spin
            }
        }
    }

    private void receiveLoop()
    {
        Util.setCpu("subcribe", Util.SUBSCRIBER_CPU);
        Thread.currentThread().setName("subscriber");
        while (!Thread.currentThread().isInterrupted())
        {
            clientSubscriber.poll(receiveMessage);
        }
    }

    private void receiveMessage(UnsafeBuffer message)
    {
        long rttNanos = System.nanoTime() - message.getLong(0);
        messageCount++;
        if (SHOULD_DELAY)
        {
            histogram.recordValueWithExpectedInterval(Math.min(MAX_VALUE, rttNanos), DELAY_NS);
        }
        else
        {
            histogram.recordValue(Math.min(MAX_VALUE, rttNanos));
        }
        if (messageCount == MESSAGE_COUNT)
        {
            try (PrintStream output = new PrintStream(
                    new FileOutputStream("/tmp/unsafe-" + System.currentTimeMillis() + ".hgram", false)))
            {
                histogram.outputPercentileDistribution(output, 1d);
            }
            catch (FileNotFoundException e)
            {
                e.printStackTrace();
            }
            histogram.reset();
            messageCount = 0;
        }
    }
}