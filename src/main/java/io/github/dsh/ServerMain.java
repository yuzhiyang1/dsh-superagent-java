package io.github.dsh;

import io.github.dsh.service.*;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** JAR 默认入口是持续运行的完整服务；Demo 仅作为单独的初学者例子保留。 */
public final class ServerMain {
    public static void main(String[] args)throws Exception{
        ServiceOptions base=ServiceOptions.defaults();Path state=base.stateDirectory();int port=base.port();boolean check=false;
        for(int i=0;i<args.length;i++)switch(args[i]){
            case "--state-dir"->{if(++i==args.length)throw new IllegalArgumentException("--state-dir needs a path");state=Path.of(args[i]);}
            case "--port"->{if(++i==args.length)throw new IllegalArgumentException("--port needs a number");port=Integer.parseInt(args[i]);}
            case "--check"->check=true;
            case "--help"->{System.out.println("Usage: java -jar dsh-superagent-java.jar [--state-dir PATH] [--port PORT] [--check]\nOPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL configure model access. Sources and all behavior are configured in the local panel. --check starts setup checks and exits with0 ready or3 setup required.");return;}
            default->throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        ServiceOptions options=new ServiceOptions(state,port,base.modelBase(),base.modelKeyEnvironment(),base.defaultModel(),true,base.environment());
        OrchestratorService service=new OrchestratorService(options);service.start();
        System.out.println("Superagent Java21: "+service.url());
        if(check){boolean ready=service.setup().path("problem").isNull();System.out.println(service.setup());service.close();if(!ready)System.exit(3);return;}
        CountDownLatch stopped=new CountDownLatch(1);Runtime.getRuntime().addShutdownHook(new Thread(()->{try{service.close();}catch(Exception error){System.err.println("Shutdown failed: "+error.getClass().getSimpleName());}finally{stopped.countDown();}},"superagent-shutdown"));
        stopped.await();
    }
}
