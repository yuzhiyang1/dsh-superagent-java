package io.github.dsh.service;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** 启动参数只保存端点和环境变量名，密钥不落盘。业务设置从管理页面版本化保存。 */
public record ServiceOptions(Path stateDirectory,int port,URI modelBase,String modelKeyEnvironment,String defaultModel,
                             boolean setupChecks,Map<String,String> environment){
    public ServiceOptions{stateDirectory=stateDirectory.toAbsolutePath().normalize();environment=Map.copyOf(environment);if(port<0||port>65535)throw new IllegalArgumentException("Invalid port");}
    public static ServiceOptions defaults(){Map<String,String> env=System.getenv();return new ServiceOptions(Path.of(env.getOrDefault("DSH_STATE_DIR","state")),Integer.parseInt(env.getOrDefault("DSH_PORT","8080")),URI.create(env.getOrDefault("OPENAI_BASE_URL","https://api.openai.com/v1/")),"OPENAI_API_KEY",env.getOrDefault("OPENAI_MODEL",""),true,env);}
    public String secret(String variable){return environment.getOrDefault(variable,"");}
}
