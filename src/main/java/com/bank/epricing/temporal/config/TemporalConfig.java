package com.bank.epricing.temporal.config;

import com.bank.epricing.temporal.activity.PricingActivitiesImpl;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring Configuration for Temporal Client and Worker.
 * Activated when epricing.temporal.enabled=true.
 */
@Configuration
@ConditionalOnProperty(name = "epricing.temporal.enabled", havingValue = "true")
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    @Value("${epricing.temporal.target:localhost:7233}")
    private String targetEndpoint;

    @Value("${epricing.temporal.namespace:default}")
    private String namespace;

    @Value("${epricing.temporal.task-queue:epricing-pricing-queue}")
    private String taskQueue;

    @Value("${epricing.temporal.worker.max-concurrent-workflow-tasks:25}")
    private int maxConcurrentWorkflowTasks;

    @Value("${epricing.temporal.worker.max-concurrent-activities:25}")
    private int maxConcurrentActivities;

    @Value("${epricing.temporal.worker.max-concurrent-local-activities:10}")
    private int maxConcurrentLocalActivities;

    @Value("${epricing.temporal.worker.workflow-task-pollers:2}")
    private int workflowTaskPollers;

    @Value("${epricing.temporal.worker.activity-task-pollers:2}")
    private int activityTaskPollers;

    @Value("${epricing.temporal.worker.max-workflow-threads:50}")
    private int maxWorkflowThreads;

    @Value("${epricing.temporal.worker.workflow-cache-size:200}")
    private int workflowCacheSize;

    private WorkerFactory workerFactory;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        log.info("Connecting to Temporal Server at {}", targetEndpoint);
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(targetEndpoint)
                .build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        log.info("Initializing Temporal WorkflowClient for namespace '{}'", namespace);
        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build()
        );
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient, PricingActivitiesImpl activities) {
        log.info("Initializing Temporal WorkerFactory for task-queue '{}' (maxWorkflowThreads={}, maxConcurrentWorkflowTasks={}, maxConcurrentActivities={})",
                taskQueue, maxWorkflowThreads, maxConcurrentWorkflowTasks, maxConcurrentActivities);
        WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
                .setMaxWorkflowThreadCount(maxWorkflowThreads)
                .setWorkflowCacheSize(workflowCacheSize)
                .build();
        this.workerFactory = WorkerFactory.newInstance(workflowClient, factoryOptions);

        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(maxConcurrentWorkflowTasks)
                .setMaxConcurrentActivityExecutionSize(maxConcurrentActivities)
                .setMaxConcurrentLocalActivityExecutionSize(maxConcurrentLocalActivities)
                .setMaxConcurrentWorkflowTaskPollers(workflowTaskPollers)
                .setMaxConcurrentActivityTaskPollers(activityTaskPollers)
                .build();
        Worker worker = workerFactory.newWorker(taskQueue, workerOptions);

        // Register workflow implementation
        worker.registerWorkflowImplementationTypes(LoanPricingWorkflowImpl.class);

        // Register activity implementation instance
        worker.registerActivitiesImplementations(activities);

        // Start worker factory upon bean initialization
        log.info("Starting Temporal Worker listening on task-queue '{}'", taskQueue);
        workerFactory.start();

        return workerFactory;
    }

    @PreDestroy
    public void stopWorkers() {
        if (workerFactory != null) {
            log.info("Stopping Temporal Worker Factory gracefully (awaiting up to 15s for in-flight tasks)");
            workerFactory.shutdown();
            workerFactory.awaitTermination(15, TimeUnit.SECONDS);
        }
    }
}
