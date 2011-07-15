package org.apache.sshd.common.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class AutoFlushOutputStream extends PipedOutputStream
{
    private static final Log log= LogFactory.getLog(AutoFlushOutputStream.class);
    private SshListener<WriteStreamEvent> writeListener=null;

    public AutoFlushOutputStream(PipedInputStream pipedInputStream)
            throws IOException
    {
        super(pipedInputStream);
    }

    public AutoFlushOutputStream()
    {
        super();
    }

    @Override
    public void write(byte[] bytes, int i, int l) throws IOException
    {
        if(log.isTraceEnabled())
        {
            log.trace("write():: ["+new String(bytes,i,l)+"]");
        }
        super.write(bytes, i, l);
        super.flush();
        if(writeListener!=null)
        {
            writeListener.run(new WriteStreamEvent(bytes,i,l));
        }
    }

    public static class WriteStreamEvent
    {
        private byte[] buffer;
        private int length;
        private int offset;

        public WriteStreamEvent(byte[] buffer, int length, int offset)
        {
            this.buffer = buffer;
            this.length = length;
            this.offset = offset;
        }

        public byte[] getBuffer()
        {
            return buffer;
        }

        public int getLength()
        {
            return length;
        }

        public int getOffset()
        {
            return offset;
        }
    }

    public void setWriteListener(SshListener<WriteStreamEvent> writeListener)
    {
        this.writeListener = writeListener;
    }
}
