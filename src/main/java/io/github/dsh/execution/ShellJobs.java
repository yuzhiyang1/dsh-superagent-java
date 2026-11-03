package io.github.dsh.execution;

import io.github.dsh.adapters.HttpJsonTransport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 真正的 ProcessBuilder 作业注册表。持续排空输出，限制保存体积，终止时同时清理后代进程。 */
public final class ShellJobs implements AutoCloseable {
    public static final class Job {
        public String id,owner,assignmentId,label;public volatile String status="running",output="",reason="";
        public long startedAt;public volatile long endedAt;public volatile Integer exitCode;public volatile boolean handedOff,truncated;
        private transient Process process; private transient CompletableFuture<Void> settled=new CompletableFuture<>();
        public Job(){}
        public boolean running(){return status.equals("running")||status.equals("stopping");}
    }
    private final Path root;private final int outputLimit;private final Duration shutdown;private final Consumer<Job> onEnd;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();private final ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
    public ShellJobs(Path state,int outputLimit,Duration shutdown,Consumer<Job> onEnd)throws IOException{
        root=state.resolve("jobs");this.outputLimit=outputLimit;this.shutdown=shutdown;this.onEnd=onEnd;WorkspaceFiles.rejectSymlinks(root);Files.createDirectories(root);
        try(var files=Files.list(root)){for(Path p:files.filter(p->p.toString().endsWith(".json")).toList()){
            Job j=HttpJsonTransport.JSON.readValue(Files.readAllBytes(p),Job.class);if(j.running()){j.status="interrupted";j.reason="Host process restarted; inspect workspace before repeating external actions";j.endedAt=System.currentTimeMillis();}
            j.settled=CompletableFuture.completedFuture(null);jobs.put(j.id,j);save(j);}}
    }
    public Job start(String owner,String assignment,String command,Path cwd,Map<String,String> environment,Duration hardLimit)throws IOException{
        return startProcess(owner,assignment,command,List.of("sh","-c",command),cwd,environment,hardLimit);
    }
    public Job startPowerShell(String owner,String assignment,String command,Path cwd,Map<String,String> environment,Duration hardLimit)throws IOException{
        return startProcess(owner,assignment,command,List.of("pwsh","-NoLogo","-NoProfile","-NonInteractive","-Command",command),cwd,environment,hardLimit);
    }
    private Job startProcess(String owner,String assignment,String command,List<String> argv,Path cwd,Map<String,String> environment,Duration hardLimit)throws IOException{
        Job j=new Job();j.id="job-"+UUID.randomUUID();j.owner=owner;j.assignmentId=assignment;j.label=command;j.startedAt=System.currentTimeMillis();jobs.put(j.id,j);save(j);
        ProcessBuilder pb=new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);pb.environment().clear();pb.environment().putAll(environment);
        try{synchronized(j){if(!j.status.equals("running"))throw new IOException("Job cancelled before process start");j.process=pb.start();}}catch(IOException e){j.status="failed";j.reason="Process could not start";j.endedAt=System.currentTimeMillis();save(j);j.settled.complete(null);throw e;}
        io.submit(()->{
            try(var stream=j.process.getInputStream();var buffer=new ByteArrayOutputStream()){
                byte[] chunk=new byte[8192];int n;while((n=stream.read(chunk))!=-1){synchronized(j){int take=Math.min(n,Math.max(0,outputLimit-buffer.size()));buffer.write(chunk,0,take);if(take<n)j.truncated=true;j.output=buffer.toString(StandardCharsets.UTF_8);}}
                j.process.waitFor();synchronized(j){j.exitCode=j.process.exitValue();if(j.status.equals("running"))j.status=j.exitCode==0?"completed":"failed";else if(j.status.equals("stopping"))j.status="killed";j.endedAt=System.currentTimeMillis();save(j);}
            }catch(Exception e){synchronized(j){if(j.running())j.status="failed";j.reason=e.getClass().getSimpleName();j.endedAt=System.currentTimeMillis();try{save(j);}catch(IOException ignored){}}}
            finally{j.settled.complete(null);onEnd.accept(j);}
        });
        if(hardLimit!=null&&!hardLimit.isZero()&&!hardLimit.isNegative())io.submit(()->{try{j.settled.get(hardLimit.toMillis(),TimeUnit.MILLISECONDS);}catch(TimeoutException e){try{kill(owner,j.id,"Command timeout");}catch(Exception ignored){}}catch(Exception ignored){}});
        return j;
    }
    public Job get(String owner,String id)throws IOException{Job j=jobs.get(id);if(j==null||!j.owner.equals(owner))throw new IOException("Job not owned by this session");return j;}
    public Job waitFor(String owner,String id,Duration timeout)throws IOException,InterruptedException{
        Job j=get(owner,id);try{j.settled.get(Math.max(0,timeout.toMillis()),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(ExecutionException e){throw new IOException(e);}return j;
    }
    public List<Job> list(String owner){return jobs.values().stream().filter(j->j.owner.equals(owner)).sorted(Comparator.comparingLong(j->j.startedAt)).toList();}
    public void handoff(String owner)throws IOException{for(Job j:list(owner))if(j.running()){j.handedOff=true;save(j);}}
    public void kill(String owner,String id,String reason)throws IOException,InterruptedException{
        Job j=get(owner,id);synchronized(j){if(!j.running())return;j.status="stopping";j.reason=reason;save(j);}
        long deadline=System.nanoTime()+shutdown.toNanos();Process p=j.process;if(p!=null){List<ProcessHandle> descendants=p.descendants().toList();for(ProcessHandle h:descendants.reversed())h.destroy();p.destroy();if(!p.waitFor(Math.min(250,shutdown.toMillis()),TimeUnit.MILLISECONDS))p.destroyForcibly();for(ProcessHandle h:descendants.reversed())if(h.isAlive())h.destroyForcibly();}
        waitFor(owner,id,Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
    }
    public List<String> killAll(String owner,String assignment,String reason,boolean includeHandedOff)throws IOException,InterruptedException{
        List<Job> live=list(owner).stream().filter(j->j.running()&&(assignment==null||assignment.equals(j.assignmentId))&&(includeHandedOff||!j.handedOff)).toList();
        List<CompletableFuture<Void>> ends=new ArrayList<>();for(Job j:live)ends.add(CompletableFuture.runAsync(()->{try{kill(owner,j.id,reason);}catch(Exception e){throw new CompletionException(e);}},io));
        try{CompletableFuture.allOf(ends.toArray(CompletableFuture[]::new)).get(shutdown.toMillis()+100,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(ExecutionException e){throw new IOException("Stopping owned jobs failed",e.getCause());}
        return live.stream().map(j->j.id).toList();
    }
    public Map<String,Object> view(Job j){Map<String,Object> m=new LinkedHashMap<>();synchronized(j){m.put("id",j.id);m.put("owner",j.owner);m.put("label",j.label);m.put("status",j.status);m.put("output",j.output);m.put("exit_code",j.exitCode);m.put("handed_off",j.handedOff);m.put("truncated",j.truncated);m.put("reason",j.reason);}return m;}
    private synchronized void save(Job job)throws IOException{WorkspaceFiles.atomicWrite(root.resolve(job.id+".json"),HttpJsonTransport.JSON.writeValueAsBytes(job));}
    @Override public void close()throws Exception{for(Job j:jobs.values())if(j.running())kill(j.owner,j.id,"Host stopped");io.shutdownNow();io.awaitTermination(shutdown.toMillis(),TimeUnit.MILLISECONDS);}
}
