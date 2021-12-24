Quartz Scheduler Hazelcast Job Store
====================================
An implementation of a Quartz Scheduler Job Store using Hazelcast distributed Maps and Sets.

This project aggregates improvements from multiple github repositories to the original implementation based on `org.terracotta.quartz.DefaultClusteredJobStore`.
Offers better compatibility with spring-boot and hazelcast 5, and makes the dependency available from Maven central.

### Adding Dependency
```
<dependency>
    <groupId>com.breakingequity</groupId>
    <artifactId>quartz-hazelcast-jobstore</artifactId>
    <version>2.0.5</version>
</dependency>
```

### About Quartz
Quartz is a richly featured, open source job scheduling library that can be integrated within virtually any Java application - from the smallest stand-alone application to the largest e-commerce system. Quartz can be used to create simple or complex schedules for executing tens, hundreds, or even tens-of-thousands of jobs; jobs whose tasks are defined as standard Java components that may execute virtually anything you may program them to do. The Quartz Scheduler includes many enterprise-class features, such as support for JTA transactions and clustering.

##### Job Stores in Quartz
JobStore's are responsible for keeping track of all the "work data" that you give to the scheduler: jobs, triggers, calendars, etc. Selecting the appropriate JobStore for your Quartz scheduler instance is an important step. Luckily, the choice should be a very easy one once you understand the differences between them. You declare which JobStore your scheduler should use (and it's configuration settings) in the properties file (or object) that you provide to the SchedulerFactory that you use to produce your scheduler instance.

[Read More](http://quartz-scheduler.org/documentation/quartz-2.x/tutorials/tutorial-lesson-09)

### About Hazelcast
Hazelcast is an in-memory open source software data grid based on Java. By having multiple nodes form a cluster, data is evenly distributed among the nodes. This allows for horizontal scaling both in terms of available storage space and processing power. Backups are also distributed in a similar fashion to other nodes, based on configuration, thereby protecting against single node failure.

[Read More](http://hazelcast.org/)

### Clustering
When using Hazelcast Job Store we rely on Hazelcast to provide a Cluster where our jobs are stored. This way we can easily have a cluster of Quartz Scheduler instances that share the same data.

### Persisting Data
Note that you can use Hazelcast MapStores to store all the data in your in-memory Maps in a datastore like Cassandra, Elasticsearch, PostgreSQL, etc (synchronously or asynchronously). Learn more about it [here](http://docs.hazelcast.org/docs/3.4/manual/html/map-persistence.html).

# Testing it

#### Tests
Tests are based on [Ameausoone/quartz-hazelcast-jobstore](https://github.com/Ameausoone/quartz-hazelcast-jobstore). 

#### Pre-requisites

* JDK 8 or newer
* Maven 3.1.0 or newer

#### Clone
```
git clone https://github.com/BreakingEquity/quartz-scheduler-hazelcast-jobstore.git
cd quartz-scheduler-hazelcast-jobstore
```
#### Build
```
mvn clean install
```

#### Release
```
mvn clean release
```
Snapshots: https://s01.oss.sonatype.org/content/repositories/snapshots/com/breakingequity/quartz-hazelcast-jobstore/
Releases: https://s01.oss.sonatype.org/content/repositories/releases/com/breakingequity/quartz-hazelcast-jobstore/ 
Maven central: https://repo.maven.apache.org/maven2/com/breakingequity/quartz-hazelcast-jobstore/

### How to use HazelcastJobStore with Quartz in spring-boot application

Sample `hazelcast.yaml`:
```yaml
hazelcast:
  cluster-name: hz-cluster
  instance-name: hz-instance
  network:
    join:
      auto-detection:
        enabled: true
```

Sample `application.yaml`:
```yaml
spring:
  quartz:
    properties:
      org:
        quartz:
          jobStore:
            class: com.breakingequity.quarz.store.hazelcast.HazelcastJobStoreDelegate
            misfireThreshold: 60000
            hazelcastInstanceName: hz-instance
```


### How to use HazelcastJobStore with Quartz
```java
// Setting Hazelcast Instance
HazelcastJobStoreDelegate.setInstance(hazelcastInstance);

// Setting Hazelcast Job Store
Properties props = new Properties();
props.setProperty(StdSchedulerFactory.PROP_JOB_STORE_CLASS, HazelcastJobStoreDelegate.class.getName());

StdSchedulerFactory scheduler = new StdSchedulerFactory(props).getScheduler();

// Starting Scheduler
scheduler.start();

// Scheduling job
JobDetail job = JobBuilder.newJob(jobClass).withIdentity(jobName, grouName).build();
Trigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerName, triggerGroup).forJob(job).startAt(new Date(startAt)).build();

scheduler.scheduleJob(job, trigger);
```
