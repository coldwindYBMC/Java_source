/*
 * Copyright (c) 1995, 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.io;

/**
 * A piped input stream should be connected
 * to a piped output stream; the piped  input
 * stream then provides whatever data bytes
 * are written to the piped output  stream.
 * Typically, data is read from a <code>PipedInputStream</code>
 * object by one thread  and data is written
 * to the corresponding <code>PipedOutputStream</code>
 * by some  other thread. Attempting to use
 * both objects from a single thread is not
 * recommended, as it may deadlock the thread.
 * The piped input stream contains a buffer,
 * decoupling read operations from write operations,
 * within limits.
 * A pipe is said to be <a name="BROKEN"> <i>broken</i> </a> if a
 * thread that was providing data bytes to the connected
 * piped output stream is no longer alive.
 *
 * @author  James Gosling
 * @see     java.io.PipedOutputStream
 * @since   JDK1.0
 */
public class PipedInputStream extends InputStream {
    boolean closedByWriter = false;//写入方主动关闭写入
    volatile boolean closedByReader = false;//读区方主动关闭，不再读取
    boolean connected = false;

        /* REMIND: identification of the read and write sides needs to be
           more sophisticated.  Either using thread groups (but what about
           pipes within a thread?) or using finalization (but it may be a
           long time until the next GC). */
    Thread readSide;//读入线程，input 主要用来判断线程状态。
    Thread writeSide;//写出线程,output

    private static final int DEFAULT_PIPE_SIZE = 1024;//默认buffer大小

    /**
     * The default size of the pipe's circular input buffer.
     * @since   JDK1.1
     */
    // This used to be a constant before the pipe size was allowed
    // to change. This field will continue to be maintained
    // for backward compatibility.
    protected static final int PIPE_SIZE = DEFAULT_PIPE_SIZE;

    /**
     * The circular buffer into which incoming data is placed.
     * @since   JDK1.1
     */
    protected byte buffer[];//缓冲区

    /**
     * The index of the position in the circular buffer at which the
     * next byte of data will be stored when received from the connected
     * piped output stream. <code>in&lt;0</code> implies the buffer is empty,
     * <code>in==out</code> implies the buffer is full
     * @since   JDK1.1
     *如果，放入的数据超过了buffer.length长度，置0，也可以理解为下次放数据从0开始。否则，in等于放入数据的长度
     *接受数据时recive(),in = out 表示buffer已满。阻塞。
     *读取数据的时候read(),in = out 表示读取完毕。 in置-1，out置0
     也表示写入的下标
     */
    protected int in = -1;//"生产者output"生产了多少,在管道内放了多少数据。这里我一般说，写入(到管道缓冲区buffer)多少。

    /**
     * The index of the position in the circular buffer at which the next
     * byte of data will be read by this piped input stream.
     * @since   JDK1.1
     */
    protected int out = 0;//消费者input消耗了多少数据，读出(从管道缓冲区)多少。也表示读区的下标

    /**
     * Creates a <code>PipedInputStream</code> so
     * that it is connected to the piped output
     * stream <code>src</code>. Data bytes written
     * to <code>src</code> will then be  available
     * as input from this stream.
     *
     * @param      src   the stream to connect to.
     * @exception  IOException  if an I/O error occurs.
     */
    public PipedInputStream(PipedOutputStream src) throws IOException {
        this(src, DEFAULT_PIPE_SIZE);
    }

    /**
     * Creates a <code>PipedInputStream</code> so that it is
     * connected to the piped output stream
     * <code>src</code> and uses the specified pipe size for
     * the pipe's buffer.
     * Data bytes written to <code>src</code> will then
     * be available as input from this stream.
     *
     * @param      src   the stream to connect to.
     * @param      pipeSize the size of the pipe's buffer.
     * @exception  IOException  if an I/O error occurs.
     * @exception  IllegalArgumentException if {@code pipeSize <= 0}.
     * @since      1.6
     */
    public PipedInputStream(PipedOutputStream src, int pipeSize)
            throws IOException {
         initPipe(pipeSize);//初始化大小，默认1024
         connect(src);  //管道连通
    }

    /**
     * Creates a <code>PipedInputStream</code> so
     * that it is not yet {@linkplain #connect(java.io.PipedOutputStream)
     * connected}.
     * It must be {@linkplain java.io.PipedOutputStream#connect(
     * java.io.PipedInputStream) connected} to a
     * <code>PipedOutputStream</code> before being used.
     */
    public PipedInputStream() {
        initPipe(DEFAULT_PIPE_SIZE);
    }

    /**
     * Creates a <code>PipedInputStream</code> so that it is not yet
     * {@linkplain #connect(java.io.PipedOutputStream) connected} and
     * uses the specified pipe size for the pipe's buffer.
     * It must be {@linkplain java.io.PipedOutputStream#connect(
     * java.io.PipedInputStream)
     * connected} to a <code>PipedOutputStream</code> before being used.
     *
     * @param      pipeSize the size of the pipe's buffer.
     * @exception  IllegalArgumentException if {@code pipeSize <= 0}.
     * @since      1.6
     */
    public PipedInputStream(int pipeSize) {
        initPipe(pipeSize);
    }

    private void initPipe(int pipeSize) {
         if (pipeSize <= 0) {
            throw new IllegalArgumentException("Pipe Size <= 0");
         }
         buffer = new byte[pipeSize];
    }

    /**
     * Causes this piped input stream to be connected
     * to the piped  output stream <code>src</code>.
     * If this object is already connected to some
     * other piped output  stream, an <code>IOException</code>
     * is thrown.
     * <p>
     * If <code>src</code> is an
     * unconnected piped output stream and <code>snk</code>
     * is an unconnected piped input stream, they
     * may be connected by either the call:
     *
     * <pre><code>snk.connect(src)</code> </pre>
     * <p>
     * or the call:
     *
     * <pre><code>src.connect(snk)</code> </pre>
     * <p>
     * The two calls have the same effect.
     *
     * @param      src   The piped output stream to connect to.
     * @exception  IOException  if an I/O error occurs.
     * 管道连通，其实是调用的output的connect()方法
     */
    public void connect(PipedOutputStream src) throws IOException {
        src.connect(this);
    }

    /**
     * Receives a byte of data.  This method will block if no input is
     * available.
     * @param b the byte being received
     * @exception IOException If the pipe is <a href="#BROKEN"> <code>broken</code></a>,
     *          {@link #connect(java.io.PipedOutputStream) unconnected},
     *          closed, or if an I/O error occurs.
     * @since     JDK1.1
     * 在output类里面调用，在缓冲区存储字节b数据。
     */
    protected synchronized void receive(int b) throws IOException {
        checkStateForReceive();
        writeSide = Thread.currentThread();//获取当前线程 write写入线程
        if (in == out)//缓冲区已满
            awaitSpace();//等待
        if (in < 0) {//缓冲区是空的，全部置0.开始存入
            in = 0;
            out = 0;
        }
        buffer[in++] = (byte)(b & 0xFF);//字节b存入缓冲区
        if (in >= buffer.length) {//写出(到管道缓冲区)的数据已经满了，置0.同时以便下次写出(管道)
            in = 0;
        }
    }

    /**
     * Receives data into an array of bytes.  This method will
     * block until some input is available.
     * @param b the buffer into which the data is received
     * @param off the start offset of the data
     * @param len the maximum number of bytes received
     * @exception IOException If the pipe is <a href="#BROKEN"> broken</a>,
     *           {@link #connect(java.io.PipedOutputStream) unconnected},
     *           closed,or if an I/O error occurs.
     */
    synchronized void receive(byte b[], int off, int len)  throws IOException {
        checkStateForReceive();
        writeSide = Thread.currentThread();//当前线程 写出线程
        int bytesToTransfer = len;//需要写入(缓冲区)的数据量
        while (bytesToTransfer > 0) {
            if (in == out)//缓冲区满了，等待
                awaitSpace();
            int nextTransferAmount = 0;//下次可以转换(写入)的量，类似，缓冲区剩下多少空间
            if (out < in) {//比如in 写入到管道缓冲区100个字节,读出了0个。同时，也保证缓冲区还没有满in不为0
                nextTransferAmount = buffer.length - in;//下次还可以写入 - in 的量
            } else if (in < out) {//比如，缓冲区满后in = 0
                if (in == -1) {       //初始化的状态
                    in = out = 0;
                    nextTransferAmount = buffer.length - in;
                } else {
                    nextTransferAmount = out - in;//out-in,剩余空间
                }
            }
            if (nextTransferAmount > bytesToTransfer)
                nextTransferAmount = bytesToTransfer;
            assert(nextTransferAmount > 0);
            System.arraycopy(b, off, buffer, in, nextTransferAmount);//数组copy
            bytesToTransfer -= nextTransferAmount;//len还剩下多少
            off += nextTransferAmount;
            in += nextTransferAmount;
            if (in >= buffer.length) {//满了，in置0
                in = 0;
            }
        }
    }

    private void checkStateForReceive() throws IOException {
        if (!connected) {
            throw new IOException("Pipe not connected");
        } else if (closedByWriter || closedByReader) {
            throw new IOException("Pipe closed");
        } else if (readSide != null && !readSide.isAlive()) {
            throw new IOException("Read end dead");
        }
    }

    private void awaitSpace() throws IOException {
        while (in == out) {
            checkStateForReceive();//检查状态

            /* full: kick any waiting readers */
            notifyAll();
            try {
                wait(1000);
            } catch (InterruptedException ex) {
                throw new java.io.InterruptedIOException();
            }
        }
    }

    /**
     * Notifies all waiting threads that the last byte of data has been
     * received.
     * 通告全部等待的线程，最后一个字节已经写入管道缓冲区
     * pipeoutput.close调用
     */
    synchronized void receivedLast() {
        closedByWriter = true;
        notifyAll();
    }

    /**
     * Reads the next byte of data from this piped input stream. The
     * value byte is returned as an <code>int</code> in the range
     * <code>0</code> to <code>255</code>.
     * This method blocks until input data is available, the end of the
     * stream is detected, or an exception is thrown.
     *
     * @return     the next byte of data, or <code>-1</code> if the end of the
     *             stream is reached.
     * @exception  IOException  if the pipe is
     *           {@link #connect(java.io.PipedOutputStream) unconnected},
     *           <a href="#BROKEN"> <code>broken</code></a>, closed,
     *           or if an I/O error occurs.
     * 读取一个字节
     */
    public synchronized int read()  throws IOException {
        if (!connected) {
            throw new IOException("Pipe not connected");
        } else if (closedByReader) {
            throw new IOException("Pipe closed");
        } else if (writeSide != null && !writeSide.isAlive()
                   && !closedByWriter && (in < 0)) {
            throw new IOException("Write end dead");
        }

        readSide = Thread.currentThread();//获得读线程
        int trials = 2;//重复两次，等待线程来读
        while (in < 0) {//初始化状态,缓冲区是空的
            if (closedByWriter) {
                /* closed by writer, return EOF */
                return -1;
            }
            if ((writeSide != null) && (!writeSide.isAlive()) && (--trials < 0)) {
                throw new IOException("Pipe broken");
            }
            /* might be a writer waiting */
            notifyAll();
            try {
                wait(1000);
            } catch (InterruptedException ex) {
                throw new java.io.InterruptedIOException();
            }
        }
        int ret = buffer[out++] & 0xFF;//读取数据，out++
        if (out >= buffer.length) {//数据全部读完
            out = 0;
        }
        if (in == out) {//初始化状态
            /* now empty */
            in = -1;
        }

        return ret;
    }

    /**
     * Reads up to <code>len</code> bytes of data from this piped input
     * stream into an array of bytes. Less than <code>len</code> bytes
     * will be read if the end of the data stream is reached or if
     * <code>len</code> exceeds the pipe's buffer size.
     * If <code>len </code> is zero, then no bytes are read and 0 is returned;
     * otherwise, the method blocks until at least 1 byte of input is
     * available, end of the stream has been detected, or an exception is
     * thrown.
     *
     * @param      b     the buffer into which the data is read.
     * @param      off   the start offset in the destination array <code>b</code>
     * @param      len   the maximum number of bytes read.
     * @return     the total number of bytes read into the buffer, or
     *             <code>-1</code> if there is no more data because the end of
     *             the stream has been reached.
     * @exception  NullPointerException If <code>b</code> is <code>null</code>.
     * @exception  IndexOutOfBoundsException If <code>off</code> is negative,
     * <code>len</code> is negative, or <code>len</code> is greater than
     * <code>b.length - off</code>
     * @exception  IOException if the pipe is <a href="#BROKEN"> <code>broken</code></a>,
     *           {@link #connect(java.io.PipedOutputStream) unconnected},
     *           closed, or if an I/O error occurs.
     * off，b数组的偏移量，
     * len，要读区的数据长度
     */
    public synchronized int read(byte b[], int off, int len)  throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        /* possibly wait on the first character 读取第一个数据，可能等待*/
        int c = read();
        if (c < 0) {
            return -1;
        }
        b[off] = (byte) c;//字节c存入
        int rlen = 1;//已经读了一个字节，这里记录一下
        while ((in >= 0) && (len > 1)) {

            int available;//这次要读多少数据

            if (in > out) { 
                available = Math.min((buffer.length - out), (in - out));
            } else {//可能in 为0，缓冲区是满的
                available = buffer.length - out;
            }

            // A byte is read beforehand outside the loop，在循环外已经有一个书库读取了，所以len-1
            if (available > (len - 1)) {
                available = len - 1;
            }
            System.arraycopy(buffer, out, b, off + rlen, available);//数组copy，off + rlen 初始数组偏移量+读取数据移动的偏移量
            out += available;//标记该表
            rlen += available;
            len -= available;

            if (out >= buffer.length) {
                out = 0;
            }
            if (in == out) {//初始化
                /* now empty */
                in = -1;
            }
        }
        return rlen;
    }

    /**
     * Returns the number of bytes that can be read from this input
     * stream without blocking.
     *
     * @return the number of bytes that can be read from this input stream
     *         without blocking, or {@code 0} if this input stream has been
     *         closed by invoking its {@link #close()} method, or if the pipe
     *         is {@link #connect(java.io.PipedOutputStream) unconnected}, or
     *          <a href="#BROKEN"> <code>broken</code></a>.
     *
     * @exception  IOException  if an I/O error occurs.
     * @since   JDK1.0.2
     * 还有多少可以读的数据
     */
    public synchronized int available() throws IOException {
        if(in < 0)
            return 0;
        else if(in == out)
            return buffer.length;
        else if (in > out)
            return in - out;
        else
            return in + buffer.length - out;
    }

    /**
     * Closes this piped input stream and releases any system resources
     * associated with the stream.
     * 关闭连接
     *
     * @exception  IOException  if an I/O error occurs.
     */
    public void close()  throws IOException {
        closedByReader = true;
        synchronized (this) {
            in = -1;
        }
    }
}
