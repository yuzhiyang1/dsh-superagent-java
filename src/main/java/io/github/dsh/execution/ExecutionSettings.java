package io.github.dsh.execution;

import io.github.dsh.core.Model.Ticket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** 执行宿主的显式能力开关。默认只读；打开 shell 后是操作系统用户权限，不是安全沙箱。 */
public final class ExecutionSettings {
    public final Path stateDirectory, workspaceRoot, template, gitRepository;
    public final String prepareCommand, baseRef;
    public final boolean allowWrites, allowShell, allowNetwork, allowSubagents, allowPublish;
    public final int maxSteps, maxSubagents, maxDepth, maxFileBytes, maxOutputBytes;
    public final Duration foregroundWait, prepareTimeout, backgroundLimit, shutdownTimeout, modelRetryBackoff;
    public final Set<String> blockedCommands;
    public final Map<String,String> environment;
    public final PromptProvider prompts;
    public final PullRequests pullRequests;
    public final WebTools web;
    private ExecutionSettings(Builder b) {
        stateDirectory=b.stateDirectory.toAbsolutePath().normalize(); workspaceRoot=b.workspaceRoot.toAbsolutePath().normalize();
        template=absolute(b.template); gitRepository=absolute(b.gitRepository); prepareCommand=b.prepareCommand; baseRef=b.baseRef;
        allowWrites=b.allowWrites; allowShell=b.allowShell; allowNetwork=b.allowNetwork; allowSubagents=b.allowSubagents; allowPublish=b.allowPublish;
        maxSteps=b.maxSteps; maxSubagents=b.maxSubagents; maxDepth=b.maxDepth; maxFileBytes=b.maxFileBytes; maxOutputBytes=b.maxOutputBytes;
        foregroundWait=b.foregroundWait; prepareTimeout=b.prepareTimeout; backgroundLimit=b.backgroundLimit; shutdownTimeout=b.shutdownTimeout;
        modelRetryBackoff=b.modelRetryBackoff; blockedCommands=Set.copyOf(b.blockedCommands); environment=Map.copyOf(b.environment);
        prompts=b.prompts; pullRequests=b.pullRequests; web=b.web;
        if(maxSteps<1||maxSteps>1000||maxSubagents<1||maxSubagents>100||maxDepth<0||maxDepth>8||maxFileBytes<1||maxOutputBytes<1)
            throw new IllegalArgumentException("Invalid execution limits");
        for(Duration d:List.of(foregroundWait,prepareTimeout,backgroundLimit,shutdownTimeout,modelRetryBackoff))
            if(d.isNegative()||d.isZero()) throw new IllegalArgumentException("Timeouts must be positive");
        if(foregroundWait.compareTo(Duration.ofSeconds(5))>0) throw new IllegalArgumentException("Worker foreground wait may not exceed 5 seconds");
        for(var e:environment.entrySet()) if(!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")||secret(e.getKey()))
            throw new IllegalArgumentException("Execution environment must not contain credentials: "+e.getKey());
        for(String name:blockedCommands) if(!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Blocked commands must be simple command names");
    }
    private static Path absolute(Path p){return p==null?null:p.toAbsolutePath().normalize();}
    public static boolean secret(String key){return key.toUpperCase(Locale.ROOT).matches(".*(KEY|PASSWORD|SECRET|TOKEN|CREDENTIAL).* ".trim());}
    public static Builder builder(Path stateDirectory, Path workspaceRoot){return new Builder(stateDirectory,workspaceRoot);}
    public record Prompt(String text, long revision, Map<String,String> skills){public Prompt{skills=Map.copyOf(skills);}}
    @FunctionalInterface public interface PromptProvider { Prompt get(Ticket ticket, String role, Map<String,String> environment) throws Exception; }
    public record PullRequest(int number,String url,String head,boolean draft) {}
    public interface PullRequests {
        PullRequest create(String title,String body,String head,String base,boolean draft) throws Exception;
        default PullRequest read(int number) throws Exception {throw new UnsupportedOperationException("PR read adapter not configured");}
    }
    public interface WebTools {
        String fetch(String url) throws Exception;
        String search(String query) throws Exception;
    }
    public static final class Builder {
        private final Path stateDirectory,workspaceRoot;
        private Path template,gitRepository; private String prepareCommand="",baseRef="HEAD";
        private boolean allowWrites,allowShell,allowNetwork,allowSubagents,allowPublish;
        private int maxSteps=100,maxSubagents=8,maxDepth=3,maxFileBytes=1024*1024,maxOutputBytes=64*1024;
        private Duration foregroundWait=Duration.ofSeconds(5),prepareTimeout=Duration.ofMinutes(10),backgroundLimit=Duration.ofMinutes(30),shutdownTimeout=Duration.ofSeconds(10),modelRetryBackoff=Duration.ofSeconds(15);
        private Set<String> blockedCommands=Set.of(); private Map<String,String> environment=Map.of();
        private PromptProvider prompts=(ticket,role,env)->new Prompt("",0,Map.of());
        private PullRequests pullRequests; private WebTools web;
        private Builder(Path stateDirectory,Path workspaceRoot){this.stateDirectory=Objects.requireNonNull(stateDirectory);this.workspaceRoot=Objects.requireNonNull(workspaceRoot);}
        public Builder template(Path v){template=v;return this;} public Builder gitRepository(Path v){gitRepository=v;return this;}
        public Builder prepareCommand(String v){prepareCommand=Objects.requireNonNull(v);return this;} public Builder baseRef(String v){baseRef=Objects.requireNonNull(v);return this;}
        public Builder allowWrites(boolean v){allowWrites=v;return this;} public Builder allowShell(boolean v){allowShell=v;return this;}
        public Builder allowNetwork(boolean v){allowNetwork=v;return this;} public Builder allowSubagents(boolean v){allowSubagents=v;return this;}
        public Builder allowPublish(boolean v){allowPublish=v;return this;}
        public Builder maxSteps(int v){maxSteps=v;return this;} public Builder maxSubagents(int v){maxSubagents=v;return this;}
        public Builder maxDepth(int v){maxDepth=v;return this;} public Builder maxFileBytes(int v){maxFileBytes=v;return this;}
        public Builder maxOutputBytes(int v){maxOutputBytes=v;return this;} public Builder foregroundWait(Duration v){foregroundWait=v;return this;}
        public Builder prepareTimeout(Duration v){prepareTimeout=v;return this;} public Builder backgroundLimit(Duration v){backgroundLimit=v;return this;}
        public Builder shutdownTimeout(Duration v){shutdownTimeout=v;return this;} public Builder modelRetryBackoff(Duration v){modelRetryBackoff=v;return this;}
        public Builder blockedCommands(Set<String> v){blockedCommands=v;return this;} public Builder environment(Map<String,String> v){environment=v;return this;}
        public Builder prompts(PromptProvider v){prompts=Objects.requireNonNull(v);return this;}
        public Builder pullRequests(PullRequests v){pullRequests=v;return this;} public Builder web(WebTools v){web=v;return this;}
        public ExecutionSettings build(){return new ExecutionSettings(this);}
    }
}
