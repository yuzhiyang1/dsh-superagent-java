package io.github.dsh.service;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** 打开数据库前锁住状态目录。OS 自动在进程退出时释放，禁止双调度者。 */
public final class StateDirectoryLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public StateDirectoryLock(Path directory)throws IOException{
        Files.createDirectories(directory);
        channel=FileChannel.open(directory.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try{lock=channel.tryLock();if(lock==null)throw new IOException("Another service owns this state directory");}
        catch(IOException|OverlappingFileLockException failure){channel.close();throw new IOException("State directory already in use",failure);}
    }
    @Override public void close()throws IOException{lock.release();channel.close();}
}
