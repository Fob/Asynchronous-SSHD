package org.apache.sshd.common.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class EventCollector<T>
{
    private boolean flag=false;
    ReentrantLock lock=new ReentrantLock();
    Condition trueFlag = lock.newCondition();

    private SshListener<T> listener= new SshListener<T>()
    {
        public void run(T event)
        {
            try
            {
                lock.lock();
                flag=true;
                trueFlag.signalAll();
            }
            finally
            {
                lock.unlock();
            }
        }
    };

    public boolean await(long timeout) throws InterruptedException
    {
        try
        {
            lock.lock();
            if(timeout==0)
                trueFlag.await();
            else
                trueFlag.await(timeout, TimeUnit.MILLISECONDS);
            return flag;
        }
        finally
        {
            flag=false;
            lock.unlock();
        }
    }

    public SshListener getSetMethod()
    {
        return listener;
    }
}
