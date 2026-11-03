package io.github.dsh.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 对齐上游的命令包装与 git 身份隔离。它建立默认身份，不宣称隔离任意 shell 程序。 */
public final class WorkerEnvironment {
    private static final String MARKER="# Managed by dsh-superagent-java\n";
    private final ExecutionSettings settings; private final Path bin,gitconfig;
    public WorkerEnvironment(ExecutionSettings settings)throws IOException{
        this.settings=settings;bin=settings.stateDirectory.resolve("worker-bin");gitconfig=settings.stateDirectory.resolve("worker-gitconfig");
        WorkspaceFiles.rejectSymlinks(bin);Files.createDirectories(bin);
        Set<String> keep=new HashSet<>(settings.blockedCommands);if(settings.environment.containsKey("DSH_SUPERAGENT_GITHUB_CLI"))keep.add("gh");if(settings.environment.containsKey("DSH_SUPERAGENT_FEISHU_CLI"))keep.add("lark-cli");if(settings.environment.containsKey("DSH_SUPERAGENT_MERGE_CLI"))keep.add("gh-merge");
        try(var existing=Files.list(bin)){for(Path p:existing.toList()){WorkspaceFiles.rejectSymlinks(p);if(!Files.isRegularFile(p)||!Files.readString(p).contains(MARKER))throw new IOException(p+" was not written by dsh-superagent-java");if(!keep.contains(p.getFileName().toString()))Files.delete(p);}}
        for(String name:settings.blockedCommands)managed(bin.resolve(name),"#!/bin/sh\n"+MARKER+"printf '%s\\n' 'Command blocked by execution settings' >&2\nexit 126\n",true);
        wrap("gh",settings.environment.get("DSH_SUPERAGENT_GITHUB_CLI"));wrap("lark-cli",settings.environment.get("DSH_SUPERAGENT_FEISHU_CLI"));wrap("gh-merge",settings.environment.get("DSH_SUPERAGENT_MERGE_CLI"));
        String cli=settings.environment.get("DSH_SUPERAGENT_GITHUB_CLI");
        String config=MARKER+"[credential]\n\thelper =\n[credential \"https://github.com\"]\n\thelper =\n";
        if(cli!=null&&!cli.isBlank())config+="\thelper = !"+quote(cli)+" auth git-credential\n";
        config+="[url \"https://github.com/\"]\n\tinsteadOf = git@github.com:\n\tinsteadOf = ssh://git@github.com/\n"
                +"[filter \"lfs\"]\n\tclean = git-lfs clean -- %f\n\tsmudge = git-lfs smudge -- %f\n\tprocess = git-lfs filter-process\n\trequired = true\n";
        managed(gitconfig,config,false);
    }
    private void wrap(String name,String target)throws IOException{
        if(target==null||target.isBlank()||settings.blockedCommands.contains(name))return;
        if(!Path.of(target).isAbsolute())throw new IOException("Account CLI must be an absolute path: "+name);
        managed(bin.resolve(name),"#!/bin/sh\n"+MARKER+"PATH="+quote(cleanPath(System.getenv("PATH")))+"; export PATH\nexec "+quote(target)+" \"$@\"\n",true);
    }
    private void managed(Path path,String content,boolean executable)throws IOException{
        WorkspaceFiles.rejectSymlinks(path);
        if(Files.exists(path)&&!Files.readString(path).contains(MARKER))throw new IOException(path+" was not written by dsh-superagent-java");
        WorkspaceFiles.atomicWrite(path,content.getBytes(StandardCharsets.UTF_8));
        if(executable&&!path.toFile().setExecutable(true,true))throw new IOException("Cannot mark command shim executable");
    }
    public Map<String,String> process(Map<String,String> variables){
        Map<String,String> result=new HashMap<>();
        System.getenv().forEach((key,value)->{if(!ExecutionSettings.secret(key)&&!key.startsWith("GIT_CONFIG")&&!Set.of("SSH_AUTH_SOCK","SSH_AGENT_PID","GIT_SSH","GIT_SSH_COMMAND","BASH_ENV","ENV").contains(key))result.put(key,value);});
        result.putAll(settings.environment);result.putAll(variables);result.put("PATH",bin+java.io.File.pathSeparator+cleanPath(result.get("PATH")));
        result.put("GIT_CONFIG_GLOBAL",gitconfig.toString());result.put("GIT_CONFIG_NOSYSTEM","1");result.put("GIT_TERMINAL_PROMPT","0");
        return result;
    }
    private String cleanPath(String path){return String.join(java.io.File.pathSeparator,Arrays.stream((path==null?"/usr/bin:/bin":path).split(java.io.File.pathSeparator)).filter(s->!s.equals(bin.toString())&&!s.isBlank()).toList());}
    public void verifyRepository(Path directory)throws IOException,InterruptedException{
        
        ProcessBuilder pb=new ProcessBuilder("git","-C",directory.toString(),"config","--local","--get-regexp","^(credential\\.|http\\..*extraheader|include\\.|includeIf\\.)");
        pb.environment().clear();pb.environment().putAll(process(Map.of()));pb.redirectErrorStream(true);
        Process p=pb.start();if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Repository credential check timed out");}
        if(p.exitValue()==0)throw new IOException("Repository-local credential/extraheader/include configuration refused");
        if(p.exitValue()!=1&&p.exitValue()!=128)throw new IOException("Cannot verify repository credential configuration");
    }
    public static String quote(String text){return "'"+text.replace("'","'\\''")+"'";}
}
