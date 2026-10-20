package com.shipora.worker.configuration;
import com.shipora.pipeline.*;
import com.shipora.worker.application.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.adapter.in.scheduling.WorkerSupervisor;
import com.shipora.worker.adapter.out.http.HttpWorkerGateway;
import com.shipora.worker.adapter.out.git.GitCheckout;
import com.shipora.worker.adapter.out.buildkit.BuildkitImageBuilder;
import com.shipora.worker.adapter.out.kubernetes.KubernetesRuntime;
import com.shipora.worker.adapter.out.docker.*;
import com.shipora.worker.adapter.out.process.LocalCommands;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.List;
@Configuration @EnableScheduling @EnableConfigurationProperties(WorkerProperties.class) @Import(PipelineConfiguration.class)
public class WorkerConfiguration {
 @Bean HttpWorkerGateway gateway(InternalHttp http,WorkerProperties properties){return new HttpWorkerGateway(http,properties);}
 @Bean CommandExecutor commands(){return new LocalCommands();}
 @Bean WorkerStartup workerStartup(WorkerProperties properties,CommandExecutor commands){return new WorkerStartup(properties,commands);}
 @Bean SourceCheckout checkout(CommandExecutor commands){return new GitCheckout(commands);}
 @Bean ImageBuilder imageBuilder(WorkerProperties properties,CommandExecutor commands){return new BuildkitImageBuilder(properties,commands);}
 @Bean RemoteDocker remoteDocker(WorkerProperties properties){return new SshDockerTransport(properties);}
 @Bean RuntimeDeployer dockerRuntime(RemoteDocker transport,WorkerProperties properties){return new DockerRuntime(transport,properties);}
 @Bean RuntimeDeployer kubernetesRuntime(WorkerProperties properties){return new KubernetesRuntime(properties);}
 @Bean WorkerEngine workerEngine(HttpWorkerGateway gateway,SourceCheckout checkout,ImageBuilder builder,List<RuntimeDeployer> runtimes){return new WorkerEngine(gateway,gateway,checkout,builder,runtimes);}
 @Bean @ConditionalOnProperty(name="shipora.worker.enabled",havingValue="true") WorkerSupervisor workerSupervisor(HttpWorkerGateway gateway,WorkerEngine engine,WorkerProperties properties){return new WorkerSupervisor(gateway,engine,properties);}
}
