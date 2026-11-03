package io.github.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.HttpJsonTransport;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * start/stop/restart/status 的 Java 控制程序。PID 只是线索，还必须核对命令、状态目录与进程启动时间。
 * 日志由独立 Java relay 持续限额轮转；不依赖 tmux、Python 或系统日志守护进程。
 */
public final class ServiceControl {
    private static final String MAIN="io.github.dsh.ServerMain";
    private static final int KEEP_LOGS=20,LOG_BYTES=5*1024*1024;
    record Options(String action,Path state,Path jar,String classpath,String java,URI url,Duration timeout,Duration stopTimeout,boolean respectStop){}
    public static void main(String[] args){int code;try{code=execute(args);}catch(Exception e){System.err.println(Objects.toString(e.getMessage(),e.getClass().getSimpleName()));code=1;}if(code!=0)System.exit(code);}
    public static int execute(String...arguments)throws Exception{
        Options options=parse(arguments);
        if(options.action.equals("supervise")){supervise(options);return 0;}
        Path control=control(options);Files.createDirectories(control);restrict(control,true);
        try(FileChannel channel=FileChannel.open(control.resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another service control operation is in progress");
            if(options.respectStop&&Set.of("start","restart").contains(options.action)&&Files.exists(marker(options))){System.out.println("Intentional stop is active; automatic recovery skipped.");return 0;}
            return switch(options.action){case "status"->status(options);case "stop"->{stop(options,true);yield 0;}case "start"->start(options);case "restart"->{stop(options,false);yield start(options);}default->throw new IllegalArgumentException("Unknown action");};
        }
    }
    private static Options parse(String[] args){
        if(args.length==0)throw new IllegalArgumentException("Usage: scripts/dsh-superagent start|stop|restart|status [--state-dir PATH] [--port PORT] [--respect-stop]");
        String action=args[0],profile="superagent",java=Path.of(System.getProperty("java.home"),"bin","java").toString(),classpath=null;Path state=null,jar=null;
        int port=Integer.parseInt(System.getenv().getOrDefault("DSH_PORT","8080"));URI url=null;Duration timeout=Duration.ofSeconds(60),stopTimeout=Duration.ofSeconds(30);boolean respect=false;
        for(int i=1;i<args.length;i++)switch(args[i]){
            case "--state-dir"->state=Path.of(value(args,++i));case "--jar"->jar=Path.of(value(args,++i));case "--classpath"->classpath=value(args,++i);case "--java"->java=value(args,++i);
            case "--profile"->profile=value(args,++i);case "--port"->port=Integer.parseInt(value(args,++i));case "--url"->url=URI.create(value(args,++i));
            case "--timeout"->timeout=seconds(value(args,++i));case "--stop-timeout"->stopTimeout=seconds(value(args,++i));case "--respect-stop"->respect=true;
            default->throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        if(!profile.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))throw new IllegalArgumentException("Invalid profile name");
        if(state==null)state=Path.of(System.getenv().getOrDefault("DSH_STATE_DIR",profile.equals("superagent")?"state":"state/profiles/"+profile));
        state=state.toAbsolutePath().normalize();if(jar!=null)jar=jar.toAbsolutePath().normalize();if(classpath==null&&jar!=null)classpath=jar.toString();
        if(classpath==null)classpath=System.getProperty("java.class.path");
        if(url==null)url=URI.create("http://127.0.0.1:"+port);
        if(!"http".equals(url.getScheme())||!Set.of("127.0.0.1","localhost","[::1]").contains(url.getHost())||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null||url.getPort()<1||url.getPort()>65535)throw new IllegalArgumentException("Management URL must be loopback HTTP with an explicit port");
        return new Options(action,state,jar,classpath,java,url,timeout,stopTimeout,respect);
    }
    private static String value(String[]args,int i){if(i>=args.length)throw new IllegalArgumentException("Option value missing");return args[i];}
    private static Duration seconds(String value){long ms=(long)(Double.parseDouble(value)*1000);if(ms<1||ms>3600000)throw new IllegalArgumentException("Timeout must be positive and at most one hour");return Duration.ofMillis(ms);}
    private static Path control(Options o){return o.state.resolve("control");}
    private static Path marker(Options o){return control(o).resolve("manually-stopped");}
    private static Path pidFile(Options o){return control(o).resolve("service.pid.json");}
    static boolean matches(String[] args,Path state,Path jar){
        boolean main=Arrays.asList(args).contains(MAIN);String candidate=null;
        for(int i=0;i<args.length;i++){
            if(args[i].equals("-jar")&&i+1<args.length&&jar!=null&&Path.of(args[i+1]).toAbsolutePath().normalize().equals(jar))main=true;
            if(args[i].equals("--state-dir")&&i+1<args.length)candidate=args[++i];
        }
        return main&&candidate!=null&&Path.of(candidate).toAbsolutePath().normalize().equals(state);
    }
    private static List<ProcessHandle> processes(Options o){
        return ProcessHandle.allProcesses().filter(ProcessHandle::isAlive).filter(p->p.info().arguments().map(a->matches(a,o.state,o.jar)).orElse(false)).sorted(Comparator.comparingLong(ProcessHandle::pid)).toList();
    }
    private static boolean verified(ProcessHandle process,Options o,Instant expectedStart){
        return process.isAlive()&&process.info().arguments().map(a->matches(a,o.state,o.jar)).orElse(false)
                &&expectedStart!=null&&process.info().startInstant().map(expectedStart::equals).orElse(false);
    }
    static int outcome(int processCount,JsonNode state){
        if(processCount!=1||state==null||!Set.of("ready","paused").contains(state.path("health").path("runtime").path("state").asText()))return 1;
        if(!state.path("setup").path("problem").isMissingNode()&&!state.path("setup").path("problem").isNull())return 3;
        var fields=state.path("health").fields();while(fields.hasNext()){var e=fields.next();if(!e.getKey().equals("runtime")&&e.getValue().path("state").asText().equals("error"))return 2;}return 0;
    }
    private static JsonNode state(Options o){
        try{
            var request=HttpRequest.newBuilder(o.url.resolve("/orchestrator/api/state")).timeout(Duration.ofSeconds(2)).header("x-orchestrator-request","1").GET().build();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build().send(request,HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200&&response.body().length()<=1024*1024?HttpJsonTransport.JSON.readTree(response.body()):null;
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return null;}
    }
    private static int status(Options o)throws IOException{
        List<ProcessHandle> pids=processes(o);JsonNode current=pids.isEmpty()?null:state(o);Map<String,Object> report=new LinkedHashMap<>();report.put("pids",pids.stream().map(ProcessHandle::pid).toList());report.put("state_directory",o.state.toString());report.put("url",o.url.toString());report.put("manually_stopped",Files.exists(marker(o)));report.put("state",current);
        System.out.println(HttpJsonTransport.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));return outcome(pids.size(),current);
    }
    private static int start(Options o)throws Exception{
        List<ProcessHandle> pids=processes(o);if(pids.size()>1)throw new IOException("Multiple matching service processes; refusing duplicate startup");
        if(pids.isEmpty()){
            if(state(o)!=null)throw new IOException("An existing orchestrator answers at this URL; refusing duplicate startup");
            Files.createDirectories(o.state.resolve("logs"));Files.createDirectories(control(o));restrict(o.state.resolve("logs"),true);
            List<String> command=new ArrayList<>();if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))command.add("nohup");
            command.addAll(List.of(o.java,"-cp",o.classpath,ServiceControl.class.getName(),"supervise","--state-dir",o.state.toString(),"--classpath",o.classpath,"--java",o.java,"--url",o.url.toString()));
            if(o.jar!=null)command.addAll(List.of("--jar",o.jar.toString()));
            Path error=control(o).resolve("supervisor-start.log");ProcessBuilder pb=new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"NUL":"/dev/null"))).redirectErrorStream(true).redirectOutput(error.toFile());pb.start();
        }else System.out.println("Service already running; no duplicate started.");
        Files.deleteIfExists(marker(o));long until=System.nanoTime()+o.timeout.toNanos();
        while(System.nanoTime()<until){JsonNode current=state(o);List<ProcessHandle> live=processes(o);int result=outcome(live.size(),current);if(result!=1)return status(o);Thread.sleep(100);}
        status(o);throw new IOException("Startup health check timed out; any running process was left intact. Inspect logs and control/supervisor-start.log.");
    }
    private static void stop(Options o,boolean intentional)throws Exception{
        List<ProcessHandle> live=processes(o);if(live.size()>1)throw new IOException("Multiple matching service processes; refusing to choose one");
        if(intentional){Files.writeString(marker(o),Instant.now()+"\n");restrict(marker(o),false);}
        for(ProcessHandle p:live){Instant started=p.info().startInstant().orElseThrow(()->new IOException("Cannot verify process start time; refusing signal"));if(!verified(p,o,started))throw new IOException("Process identity changed; refusing signal");p.destroy();
            try{p.onExit().get(o.stopTimeout.toMillis(),TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException e){throw new IOException("Graceful stop timed out; no forced kill was sent");}}
        Files.deleteIfExists(pidFile(o));System.out.println(intentional?"Service stopped. Automatic recovery remains paused until start/restart.":"Service stopped.");
    }
    private static void supervise(Options o)throws Exception{
        Files.createDirectories(control(o));List<String> cmd=List.of(o.java,"-cp",o.classpath,MAIN,"--state-dir",o.state.toString(),"--port",Integer.toString(o.url.getPort()));
        Process child=new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"NUL":"/dev/null"))).start();
        Map<String,Object> identity=Map.of("pid",child.pid(),"started",child.info().startInstant().orElseThrow().toString(),"state",o.state.toString());Files.write(pidFile(o),HttpJsonTransport.JSON.writeValueAsBytes(identity));restrict(pidFile(o),false);
        try(var stream=child.getInputStream();var logs=new RollingLogs(o.state.resolve("logs"),KEEP_LOGS,LOG_BYTES)){
            byte[] bytes=new byte[8192];int read;while((read=stream.read(bytes))!=-1)logs.write(bytes,0,read);
        }finally{child.waitFor();try{JsonNode recorded=HttpJsonTransport.JSON.readTree(Files.readAllBytes(pidFile(o)));if(recorded.path("pid").asLong()==child.pid())Files.deleteIfExists(pidFile(o));}catch(IOException ignored){}}
    }
    private static void restrict(Path path,boolean directory){try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}catch(IOException|UnsupportedOperationException ignored){}}
    static final class RollingLogs implements AutoCloseable{
        final Path directory;final int keep,max;OutputStream output;long size;int part;
        RollingLogs(Path directory,int keep,int max)throws IOException{this.directory=directory;this.keep=keep;this.max=max;Files.createDirectories(directory);rotate();}
        void write(byte[] bytes,int offset,int length)throws IOException{while(length>0){if(size>=max)rotate();int n=(int)Math.min(length,max-size);output.write(bytes,offset,n);output.flush();size+=n;offset+=n;length-=n;}}
        private void rotate()throws IOException{
            if(output!=null)output.close();Path file=directory.resolve("superagent-"+Instant.now().toEpochMilli()+"-"+ProcessHandle.current().pid()+"-"+(part++)+".log");output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW);restrict(file,false);size=0;
            try(var list=Files.list(directory)){List<Path> logs=list.filter(p->p.getFileName().toString().matches("superagent-[0-9]+-[0-9]+-[0-9]+\\.log")).sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return Long.MAX_VALUE;}})).toList();for(int i=0;i<logs.size()-keep;i++)Files.deleteIfExists(logs.get(i));}
        }
        @Override public void close()throws IOException{if(output!=null)output.close();}
    }
}
