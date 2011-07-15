package org.apache.sshd.common.util;


import java.util.EventListener;

public interface SshListener<T> extends EventListener
{
    void run(T event);
}
