package io.github.dsh.sources;

import io.github.dsh.adapters.HttpJsonTransport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java 托管的原型 lark-cli live transport：无 shell，准备超时、禁用中止、崩溃重连，补拉由持久轮询器负责。 */
public final class FeishuEventConsumer implements AutoCloseable {
    private final FeishuSource source;private final SourceContext context;private final String executable;
    private volatile boolean closing;private volatile Process process;private Thread thread;
    public FeishuEventConsumer(FeishuSource source,SourceContext context,String executable){this.source=source;this.context=context;this.executable=executable;}
    public synchronized void start(){if(thread!=null)throw new IllegalStateException("Consumer already started");thread=Thread.ofVirtual().name("feishu-events-"+source.id()).start(this::loop);}
    public static long retryMillis(Long previous,long ranMs){return previous==null||ranMs>=60000?1000:Math.min(previous*2,60000);}
    private void loop(){Long retry=null;
        while(!closing){
            if(!source.enabled()){pause(1000);continue;}
            long began=System.nanoTime();
            try{consume();}catch(Exception error){if(!closing)context.state(source.id(),"live-health",Map.of("state","error","error",error.getClass().getSimpleName(),"at",context.now().toString()));}
            if(closing)break;retry=retryMillis(retry,Duration.ofNanos(System.nanoTime()-began).toMillis());pause(retry);
        }
    }
    private void consume()throws Exception{
        ProcessBuilder builder=new ProcessBuilder(executable,"event","consume","im.message.receive_v1","--as","bot");
        builder.environment().put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER","1");builder.environment().put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER","1");
        Process running=builder.start();process=running;AtomicBoolean ready=new AtomicBoolean();
        Thread stderr=Thread.ofVirtual().start(()->{try{String line;while((line=readLine(running.getErrorStream(),65536))!=null){if(line.startsWith("[event] ready")){ready.set(true);context.state(source.id(),"live-health",Map.of("state","ready","at",context.now().toString()));}}}catch(Exception ignored){running.destroy();}});
        Thread guard=Thread.ofVirtual().start(()->{
            long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
            while(running.isAlive()&&!closing){if(!source.enabled()||(!ready.get()&&System.nanoTime()>deadline)){running.destroy();break;}try{Thread.sleep(500);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}}
            if(closing)running.destroy();
        });
        try{String line;while(!closing&&(line=readLine(running.getInputStream(),1024*1024))!=null){if(line.isBlank())continue;
                try{source.receiveCli(context,HttpJsonTransport.JSON.readTree(line));}catch(Exception error){context.state(source.id(),"live-health",Map.of("state","error","error","invalid_event","at",context.now().toString()));}
            }
            int exit=running.waitFor();if(!closing&&source.enabled())throw new IOException("Feishu event process exited "+exit);
        }finally{running.destroy();if(!running.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))running.destroyForcibly();stderr.interrupt();guard.interrupt();process=null;}
    }
    /** 不使用无界 BufferedReader.readLine，恶意或损坏的一行不会耗尽堆。 */
    static String readLine(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream line=new ByteArrayOutputStream();int next;
        while((next=input.read())!=-1){if(next=='\n')break;if(line.size()>=maximum)throw new IOException("Event line exceeds limit");line.write(next);}
        if(next==-1&&line.size()==0)return null;return line.toString(StandardCharsets.UTF_8).stripTrailing();
    }
    private void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();closing=true;}}
    @Override public void close(){closing=true;Process running=process;if(running!=null){running.destroy();try{if(!running.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))running.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(thread!=null){thread.interrupt();try{thread.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}
