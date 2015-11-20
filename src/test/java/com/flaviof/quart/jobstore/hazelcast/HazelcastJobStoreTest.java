package com.flaviof.quart.jobstore.hazelcast;

import com.beust.jcommander.internal.Maps;
import com.google.common.collect.Lists;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quartz.Calendar;
import org.quartz.DateBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import static org.quartz.Scheduler.DEFAULT_GROUP;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.jobs.NoOpJob;
import org.quartz.simpl.CascadingClassLoadHelper;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.internal.annotations.Sets;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class HazelcastJobStoreTest {

  static final Logger LOG = LoggerFactory.getLogger(HazelcastJobStoreTest.class);

  private HazelcastInstance hazelcastInstance;
  private JobStore jobStore;
  private SampleSignaler fSignaler;
  private int buildTriggerIndex = 0;
  private int buildJobIndex = 0;

  private JobDetail jobDetail;

  @BeforeClass
  public void setUp()
    throws SchedulerException, InterruptedException {

    fSignaler = new SampleSignaler();

    Config config = new Config();
    config.setProperty("hazelcast.logging.type", "slf4j");
    hazelcastInstance = Hazelcast.newHazelcastInstance(config);

    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();

    jobStore = createJobStore("AbstractJobStoreTest");
    jobStore.initialize(loadHelper, this.fSignaler);
    jobStore.schedulerStarted();

    jobDetail = JobBuilder.newJob(NoOpJob.class).withIdentity("job1", "jobGroup1").build();
    jobStore.storeJob(jobDetail, false);
  }

  @AfterClass
  public void tearDown() {

    hazelcastInstance.shutdown();
  }

  @AfterMethod
  public void cleanUpAfterEachTest()
    throws JobPersistenceException {

    jobStore.clearAllSchedulingData();

  }

  @AfterClass
  public void cleanUp() {

    jobStore.shutdown();
    hazelcastInstance.shutdown();
  }

  @Test()
  public void testAcquireNextTrigger()
    throws Exception {

    Date baseFireTimeDate = DateBuilder.evenMinuteDateAfterNow();
    long baseFireTime = baseFireTimeDate.getTime();

    JobDetail job = JobBuilder.newJob(NoOpJob.class).build();
    jobStore.storeJob(job, true);

    OperableTrigger t1 = createAndComputeTrigger("trigger1", "triggerGroup1", job, baseFireTime + 2000);
    OperableTrigger t2 = createAndComputeTrigger("trigger2", "triggerGroup1", job, baseFireTime + 500);
    OperableTrigger t3 = createAndComputeTrigger("trigger3", "triggerGroup2", job, baseFireTime + 1000);

    assertTrue(jobStore.acquireNextTriggers(baseFireTime, 1, 0L).isEmpty());

    jobStore.storeTrigger(t1, false);
    assertEquals(jobStore.acquireNextTriggers(baseFireTime + 2000, 1, 0L).get(0), t1);

    jobStore.storeTrigger(t2, false);
    assertEquals(jobStore.acquireNextTriggers(baseFireTime + 600, 1, 0L).get(0), t2);

    assertTrue(jobStore.acquireNextTriggers(baseFireTime + 600, 1, 0L).isEmpty());

    jobStore.storeTrigger(t3, false);
    assertEquals(jobStore.acquireNextTriggers(baseFireTime + 5000, 1, 0L).get(0), t3);

    // release trigger3
    jobStore.releaseAcquiredTrigger(t3);
    assertEquals(jobStore.acquireNextTriggers(t3.getNextFireTime().getTime() + 5000, 1, 1L).get(0), t3);

    assertTrue(jobStore.acquireNextTriggers(baseFireTime + 10000, 1, 0L).isEmpty());

    jobStore.removeTrigger(t1.getKey());
    jobStore.removeTrigger(t2.getKey());
    jobStore.removeTrigger(t3.getKey());
  }

  @Test()
  public void testAcquireNextTriggerAfterMissFire()
    throws Exception {

    long baseFireTime = DateBuilder.newDate().build().getTime();

    JobDetail job = JobBuilder.newJob(NoOpJob.class).build();
    jobStore.storeJob(job, true);

    OperableTrigger t1 = createAndComputeTrigger("trigger1", "triggerGroup1", job, baseFireTime + 500);
    OperableTrigger t2 = createAndComputeTrigger("trigger2", "triggerGroup1", job, baseFireTime + 500);

    jobStore.storeTrigger(t1, false);
    jobStore.storeTrigger(t2, false);

    assertEquals(jobStore.acquireNextTriggers(baseFireTime + 600, 1, 0L).size(), 1);

    Thread.sleep(6000);

    assertEquals(jobStore.acquireNextTriggers(DateBuilder.newDate().build().getTime() + 600, 1, 0L).size(), 0);

    OperableTrigger missfiredTriger = jobStore.getTriggersForJob(job.getKey())
        .stream()
        .filter(item
            -> item.getNextFireTime().getTime() != t1.getNextFireTime().getTime()
                && item.getNextFireTime().getTime() != t2.getNextFireTime().getTime())
        .findFirst()
        .get();

    assertEquals(jobStore.acquireNextTriggers(missfiredTriger.getNextFireTime().getTime() + 600, 1, 0L).size(), 1);

    jobStore.removeTrigger(t1.getKey());
    jobStore.removeTrigger(t2.getKey());
  }

  @Test()
  public void testAcquireNextTriggerBatch()
    throws Exception {

    Date baseFireTimeDate = DateBuilder.evenMinuteDateAfterNow();
    long baseFireTime = baseFireTimeDate.getTime();

    jobStore.storeJob(jobDetail, true);

    OperableTrigger trigger1 = createTrigger("trigger1",
        "triggerGroup1",
        jobDetail,
        baseFireTime + 200000,
        baseFireTime + 200005);
    OperableTrigger trigger2 = createTrigger("trigger2",
        "triggerGroup1",
        jobDetail,
        baseFireTime + 200100,
        baseFireTime + 200105);
    OperableTrigger trigger3 = createTrigger("trigger3",
        "triggerGroup1",
        jobDetail,
        baseFireTime + 200200,
        baseFireTime + 200205);
    OperableTrigger trigger4 = createTrigger("trigger4",
        "triggerGroup1",
        jobDetail,
        baseFireTime + 200300,
        baseFireTime + 200305);

    OperableTrigger trigger5 = createTrigger("trigger5",
        "triggerGroup2",
        jobDetail,
        baseFireTime + 500000,
        baseFireTime + 700000);

    trigger1.computeFirstFireTime(null);
    trigger2.computeFirstFireTime(null);
    trigger3.computeFirstFireTime(null);
    trigger4.computeFirstFireTime(null);
    trigger5.computeFirstFireTime(null);

    jobStore.storeTrigger(trigger1, false);
    jobStore.storeTrigger(trigger2, false);
    jobStore.storeTrigger(trigger3, false);
    jobStore.storeTrigger(trigger4, false);
    jobStore.storeTrigger(trigger5, false);

    long firstFireTime = new Date(trigger1.getNextFireTime().getTime())
        .getTime();

    List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(firstFireTime + 10000, 3, 1000L);
    assertEquals(3, acquiredTriggers.size());

    // release all the triggers since there is no order ensurance
    jobStore.releaseAcquiredTrigger(trigger1);
    jobStore.releaseAcquiredTrigger(trigger2);
    jobStore.releaseAcquiredTrigger(trigger3);
    jobStore.releaseAcquiredTrigger(trigger4);

    acquiredTriggers = jobStore.acquireNextTriggers(firstFireTime + 10000, 4, 1000L);
    assertEquals(4, acquiredTriggers.size());

    // release all the triggers since there is no order ensurance
    jobStore.releaseAcquiredTrigger(trigger1);
    jobStore.releaseAcquiredTrigger(trigger2);
    jobStore.releaseAcquiredTrigger(trigger3);
    jobStore.releaseAcquiredTrigger(trigger4);

    acquiredTriggers = jobStore.acquireNextTriggers(firstFireTime + 10000, 5, 1000L);
    assertEquals(4, acquiredTriggers.size());

    jobStore.releaseAcquiredTrigger(trigger1);
    jobStore.releaseAcquiredTrigger(trigger2);

    assertEquals(1, jobStore.acquireNextTriggers(firstFireTime + 1, 5, 0L).size());
    jobStore.releaseAcquiredTrigger(trigger1);

    assertEquals(2, jobStore.acquireNextTriggers(firstFireTime + 250, 5, 199L).size());
    jobStore.releaseAcquiredTrigger(trigger1);

    assertEquals(1, jobStore.acquireNextTriggers(firstFireTime + 150, 5, 50L).size());
    jobStore.releaseAcquiredTrigger(trigger1);

    jobStore.removeTrigger(trigger1.getKey());
    jobStore.removeTrigger(trigger2.getKey());
    jobStore.removeTrigger(trigger3.getKey());
    jobStore.removeTrigger(trigger4.getKey());
    jobStore.removeTrigger(trigger5.getKey());
  }

  @Test()
  public void testTriggerStates()
    throws Exception {

    jobStore.storeJob(jobDetail, false);

    OperableTrigger trigger = createTrigger("trigger1",
        "triggerGroup1",
        jobDetail,
        System.currentTimeMillis() + 100000,
        System.currentTimeMillis() + 200000);

    trigger.computeFirstFireTime(null);

    assertEquals(Trigger.TriggerState.NONE, jobStore.getTriggerState(trigger.getKey()));

    jobStore.storeTrigger(trigger, false);
    assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));

    jobStore.pauseTrigger(trigger.getKey());
    assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(trigger.getKey()));

    jobStore.resumeTrigger(trigger.getKey());
    assertEquals(Trigger.TriggerState.NORMAL, jobStore.getTriggerState(trigger.getKey()));

    OperableTrigger rt1 = jobStore.acquireNextTriggers(
        new Date(trigger.getNextFireTime().getTime()).getTime() + 10000,
        1,
        1L)
        .get(0);

    assertNotNull(rt1);
    jobStore.releaseAcquiredTrigger(rt1);

    OperableTrigger rt2 = jobStore.acquireNextTriggers(
        new Date(rt1.getNextFireTime().getTime()).getTime() + 10000,
        1,
        1L)
        .get(0);

    assertNotNull(rt2);
    assertTrue(jobStore.acquireNextTriggers(new Date(rt2.getNextFireTime().getTime()).getTime() + 10000,
        1,
        1L)
        .isEmpty());
  }

  @Test()
  public void testStoreTriggerReplacesTrigger()
    throws Exception {

    JobDetail job = createJob("replacesTrigJob99", "replacesTrigJobGroup");
    jobStore.storeJob(job, false);

    OperableTrigger tr = createTrigger("stReplacesTrigger1", "stReplacesTriggerGroup", job, new Date().getTime());
    tr.setCalendarName(null);

    jobStore.storeTrigger(tr, false);
    assertEquals(tr, jobStore.retrieveTrigger(tr.getKey()));

    try {
      jobStore.storeTrigger(tr, false);
      fail("an attempt to store duplicate trigger succeeded");
    } catch (ObjectAlreadyExistsException ex) {
      // expected
    }

    tr.setCalendarName("QQ");
    jobStore.storeTrigger(tr, true);
    assertEquals(tr, jobStore.retrieveTrigger(tr.getKey()));
    assertEquals("QQ", jobStore.retrieveTrigger(tr.getKey()).getCalendarName(), "StoreJob doesn't replace triggers");
  }

  @Test()
  public void testPauseJobGroupPausesNewJob()
    throws Exception {

    final String jobGroup = "PauseJobGroupPausesNewJobGroup";

    JobDetail job1 = createJob("PauseJobGroupPausesNewJob", jobGroup);
    jobStore.storeJob(job1, false);
    jobStore.pauseJobs(GroupMatcher.jobGroupEquals(jobGroup));

    JobDetail job2 = createJob("PauseJobGroupPausesNewJob2", jobGroup);
    jobStore.storeJob(job2, false);

    OperableTrigger tr = createTrigger(
        "PauseJobGroupPausesNewJobTrigger",
        "PauseJobGroupPausesNewJobTriggerGroup",
        job2,
        new Date().getTime());

    jobStore.storeTrigger(tr, false);
    assertEquals(Trigger.TriggerState.PAUSED, jobStore.getTriggerState(tr.getKey()));
  }

  @Test()
  public void testStoreAndRetrieveJobs()
    throws Exception {

    final int nJobs = 10;

    SchedulerSignaler schedSignaler = new SampleSignaler();
    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();

    JobStore store = createJobStore("testStoreAndRetrieveJobs");
    store.initialize(loadHelper, schedSignaler);

    // Store jobs.
    for (int i = 0; i < nJobs; i++) {
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job" + i).build();
      store.storeJob(job, false);
    }

    // Retrieve jobs.
    for (int i = 0; i < nJobs; i++) {
      JobKey jobKey = JobKey.jobKey("job" + i);
      JobDetail storedJob = store.retrieveJob(jobKey);
      Assert.assertEquals(jobKey, storedJob.getKey());
    }
  }

  @Test()
  public void testStoreAndRetriveTriggers()
    throws Exception {

    final int nJobs = 10;

    SchedulerSignaler schedSignaler = new SampleSignaler();
    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();

    JobStore store = createJobStore("testStoreAndRetriveTriggers");
    store.initialize(loadHelper, schedSignaler);

    // Store jobs and triggers.
    for (int i = 0; i < nJobs; i++) {
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job" + i).build();
      store.storeJob(job, true);

      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule();
      Trigger trigger = TriggerBuilder.newTrigger().withIdentity("job" + i).withSchedule(schedule).forJob(job).build();
      store.storeTrigger((OperableTrigger) trigger, true);
    }
    // Retrieve jobs and triggers.
    for (int i = 0; i < nJobs; i++) {
      JobKey jobKey = JobKey.jobKey("job" + i);
      JobDetail storedJob = store.retrieveJob(jobKey);
      Assert.assertEquals(jobKey, storedJob.getKey());

      TriggerKey triggerKey = TriggerKey.triggerKey("job" + i);
      Trigger storedTrigger = store.retrieveTrigger(triggerKey);
      Assert.assertEquals(triggerKey, storedTrigger.getKey());
    }
  }

  @Test()
  public void testAcquireTriggers()
    throws Exception {

    final int nJobs = 10;

    SchedulerSignaler schedSignaler = new SampleSignaler();
    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();

    JobStore store = createJobStore("testAcquireTriggers");
    store.initialize(loadHelper, schedSignaler);

    // Setup: Store jobs and triggers.
    long MIN = 60 * 1000L;
    Date startTime0 = new Date(System.currentTimeMillis() + MIN); // a min from
    // now.
    for (int i = 0; i < nJobs; i++) {
      Date startTime = new Date(startTime0.getTime() + i * MIN); // a min apart
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job" + i).build();
      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
      OperableTrigger trigger = (OperableTrigger) TriggerBuilder
          .newTrigger()
          .withIdentity("job" + i)
          .withSchedule(schedule).forJob(job)
          .startAt(startTime)
          .build();

      // Manually trigger the first fire time computation that scheduler would
      // do. Otherwise
      // the store.acquireNextTriggers() will not work properly.
      Date fireTime = trigger.computeFirstFireTime(null);
      Assert.assertEquals(true, fireTime != null);

      store.storeJobAndTrigger(job, trigger);
    }

    // Test acquire one trigger at a time
    for (int i = 0; i < nJobs; i++) {
      long noLaterThan = (startTime0.getTime() + i * MIN);
      int maxCount = 1;
      long timeWindow = 0;
      List<OperableTrigger> triggers = store.acquireNextTriggers(noLaterThan, maxCount, timeWindow);
      Assert.assertEquals(1, triggers.size());
      Assert.assertEquals("job" + i, triggers.get(0).getKey().getName());

      // Let's remove the trigger now.
      store.removeJob(triggers.get(0).getJobKey());
    }
  }

  @Test()
  public void testAcquireTriggersInBatch()
    throws Exception {

    SchedulerSignaler schedSignaler = new SampleSignaler();
    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();

    JobStore store = createJobStore("testAcquireTriggersInBatch");
    store.initialize(loadHelper, schedSignaler);

    // Setup: Store jobs and triggers.
    long MIN = 60 * 1000L;
    Date startTime0 = new Date(System.currentTimeMillis() + MIN); // a min from
    // now.
    for (int i = 0; i < 10; i++) {
      Date startTime = new Date(startTime0.getTime() + i * MIN); // a min apart
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job" + i).build();
      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
      OperableTrigger trigger = (OperableTrigger) TriggerBuilder.newTrigger()
          .withIdentity("job" + i)
          .withSchedule(schedule)
          .forJob(job)
          .startAt(startTime)
          .build();

      // Manually trigger the first fire time computation that scheduler would
      // do. Otherwise
      // the store.acquireNextTriggers() will not work properly.
      Date fireTime = trigger.computeFirstFireTime(null);
      Assert.assertEquals(true, fireTime != null);

      store.storeJobAndTrigger(job, trigger);
    }

    // Test acquire batch of triggers at a time
    long noLaterThan = startTime0.getTime() + 10 * MIN;
    int maxCount = 7;
    // time window needs to be big to be able to pick up multiple triggers when
    // they are a minute apart
    long timeWindow = 8 * MIN;
    List<OperableTrigger> triggers = store.acquireNextTriggers(noLaterThan, maxCount, timeWindow);
    Assert.assertEquals(7, triggers.size());
  }

  @Test
  public void testStoreSimpleJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String jobName = "job20";
    storeJob(jobName);
    JobDetail retrieveJob = retrieveJob(jobName);
    assertNotNull(retrieveJob);
  }

  @Test(expectedExceptions = { ObjectAlreadyExistsException.class })
  public void storeTwiceSameJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String jobName = "job21";
    storeJob(jobName);
    storeJob(jobName);
  }

  @Test
  public void testRemoveJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String jobName = "job22";
    JobDetail jobDetail = buildJob(jobName);
    storeJob(jobDetail);

    JobDetail retrieveJob = retrieveJob(jobName);
    assertNotNull(retrieveJob);

    Trigger trigger = buildTrigger(jobDetail);
    storeTrigger(trigger);

    assertNotNull(retrieveTrigger(trigger.getKey()));

    boolean removeJob = jobStore.removeJob(jobDetail.getKey());
    assertTrue(removeJob);
    retrieveJob = retrieveJob(jobName);
    assertNull(retrieveJob);

    assertNull(retrieveTrigger(trigger.getKey()));

    removeJob = jobStore.removeJob(jobDetail.getKey());
    assertFalse(removeJob);
  }

  @Test
  public void testRemoveJobs()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String jobName = "job24";
    JobDetail jobDetail = buildJob(jobName);

    String jobName2 = "job25";
    JobDetail jobDetailImpl2 = buildJob(jobName2);

    jobStore.storeJob(jobDetail, false);
    jobStore.storeJob(jobDetailImpl2, false);

    JobDetail retrieveJob = retrieveJob(jobName);
    assertNotNull(retrieveJob);

    List<JobKey> jobKeyList = Lists.newArrayList(jobDetail.getKey(), jobDetailImpl2.getKey());
    boolean removeJob = jobStore.removeJobs(jobKeyList);
    assertTrue(removeJob);

    retrieveJob = retrieveJob(jobName);
    assertNull(retrieveJob);
    retrieveJob = retrieveJob(jobName2);
    assertNull(retrieveJob);

    removeJob = jobStore.removeJob(jobDetail.getKey());
    assertFalse(removeJob);
    removeJob = jobStore.removeJob(jobDetailImpl2.getKey());
    assertFalse(removeJob);
  }

  @Test
  public void testCheckExistsJob()
    throws JobPersistenceException {

    JobDetail jobDetailImpl = buildJob("job23");
    jobStore.storeJob(jobDetailImpl, false);
    boolean checkExists = jobStore.checkExists(jobDetailImpl.getKey());
    assertTrue(checkExists);
  }

  @Test(expectedExceptions = { JobPersistenceException.class })
  public void testStoreTriggerWithoutJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger("trigger", "group");
    storeTrigger(trigger1);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
  }

  @Test
  public void testStoreTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger();
    storeTrigger(trigger1);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
  }

  @Test(expectedExceptions = { ObjectAlreadyExistsException.class })
  public void testStoreTriggerThrowsAlreadyExists()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    Trigger trigger1 = buildTrigger();
    storeTrigger(trigger1);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
    storeTrigger(trigger1);
    retrieveTrigger = retrieveTrigger(trigger1.getKey());
  }

  @Test
  public void testStoreTriggerTwice()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger();

    storeTrigger(trigger1);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
    jobStore.storeTrigger((OperableTrigger) trigger1, true);
    retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
  }

  @Test
  public void testRemoveTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail storeJob = storeJob(buildJob("job"));
    Trigger trigger1 = buildTrigger(storeJob);
    TriggerKey triggerKey = trigger1.getKey();
    storeTrigger(trigger1);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
    boolean removeTrigger = jobStore.removeTrigger(triggerKey);
    assertTrue(removeTrigger);
    retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNull(retrieveTrigger);
    removeTrigger = jobStore.removeTrigger(triggerKey);
    assertFalse(removeTrigger);

    TriggerState triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.NONE);
  }

  @Test
  public void testRemoveTriggers()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger();
    Trigger trigger2 = buildTrigger();

    storeTrigger(trigger1);
    storeTrigger(trigger2);

    List<TriggerKey> triggerKeys = Lists.newArrayList(trigger1.getKey(), trigger2.getKey());
    boolean removeTriggers = jobStore.removeTriggers(triggerKeys);
    assertTrue(removeTriggers);
  }

  @Test
  public void testTriggerCheckExists()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger();
    TriggerKey triggerKey = trigger1.getKey();

    boolean checkExists = jobStore.checkExists(triggerKey);
    assertFalse(checkExists);

    storeTrigger(trigger1);

    checkExists = jobStore.checkExists(triggerKey);
    assertTrue(checkExists);
  }

  @Test
  public void testReplaceTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger1 = buildTrigger();

    storeTrigger(trigger1);

    Trigger newTrigger = buildTrigger();

    TriggerKey triggerKey = trigger1.getKey();
    boolean replaceTrigger = jobStore.replaceTrigger(triggerKey, (OperableTrigger) newTrigger);
    assertTrue(replaceTrigger);
    Trigger retrieveTrigger = jobStore.retrieveTrigger(triggerKey);
    assertEquals(retrieveTrigger, newTrigger);
  }

  @Test
  public void testStoreJobAndTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail jobDetailImpl = buildJob("job30");

    Trigger trigger1 = buildTrigger();
    jobStore.storeJobAndTrigger(jobDetailImpl, (OperableTrigger) trigger1);
    JobDetail retrieveJob = jobStore.retrieveJob(jobDetailImpl.getKey());
    assertNotNull(retrieveJob);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);
  }

  @Test(expectedExceptions = { ObjectAlreadyExistsException.class })
  public void testStoreJobAndTriggerThrowJobAlreadyExists()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    JobDetail jobDetailImpl = buildJob("job31");
    Trigger trigger1 = buildTrigger();
    jobStore.storeJobAndTrigger(jobDetailImpl, (OperableTrigger) trigger1);
    JobDetail retrieveJob = jobStore.retrieveJob(jobDetailImpl.getKey());
    assertNotNull(retrieveJob);
    Trigger retrieveTrigger = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger);

    jobStore.storeJobAndTrigger(jobDetailImpl, (OperableTrigger) trigger1);
  }

  @Test
  public void storeCalendar()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String calName = "calendar";
    storeCalendar(calName);
    Calendar retrieveCalendar = jobStore.retrieveCalendar(calName);
    assertNotNull(retrieveCalendar);
  }

  @Test
  public void testRemoveCalendar()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    String calName = "calendar1";
    storeCalendar(calName);

    Calendar retrieveCalendar = jobStore.retrieveCalendar(calName);
    assertNotNull(retrieveCalendar);
    boolean calendarExisted = jobStore.removeCalendar(calName);
    assertTrue(calendarExisted);
    retrieveCalendar = jobStore.retrieveCalendar(calName);
    assertNull(retrieveCalendar);
    calendarExisted = jobStore.removeCalendar(calName);
    assertFalse(calendarExisted);

  }

  @Test
  public void testClearAllSchedulingData()
    throws JobPersistenceException {

    assertEquals(jobStore.getNumberOfJobs(), 0);

    assertEquals(jobStore.getNumberOfTriggers(), 0);

    assertEquals(jobStore.getNumberOfCalendars(), 0);

    final String jobName = "job40";
    final JobDetail storeJob = storeJob(jobName);
    assertEquals(jobStore.getNumberOfJobs(), 1);

    jobStore.storeTrigger((OperableTrigger) buildTrigger(storeJob), false);
    assertEquals(jobStore.getNumberOfTriggers(), 1);

    jobStore.storeCalendar("calendar", new BaseCalendar(), false, false);
    assertEquals(jobStore.getNumberOfCalendars(), 1);

    jobStore.clearAllSchedulingData();
    assertEquals(jobStore.getNumberOfJobs(), 0);

    assertEquals(jobStore.getNumberOfTriggers(), 0);

    assertEquals(jobStore.getNumberOfCalendars(), 0);
  }

  @Test
  public void testStoreSameJobNameWithDifferentGroup()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    storeJob(buildJob("job40", "group1"));
    storeJob(buildJob("job40", "group2"));
    // Assert there is no exception throws
  }

  @Test
  public void testGetJobGroupNames()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail buildJob = buildJob("job40", "group1");
    storeJob(buildJob);
    storeJob(buildJob("job41", "group2"));
    List<String> jobGroupNames = jobStore.getJobGroupNames();
    assertEquals(jobGroupNames.size(), 2);
    assertTrue(jobGroupNames.contains("group1"));
    assertTrue(jobGroupNames.contains("group2"));

    jobStore.removeJob(buildJob.getKey());

    jobGroupNames = jobStore.getJobGroupNames();
    assertEquals(jobGroupNames.size(), 1);
    assertTrue(jobGroupNames.contains("group2"));
  }

  @Test
  public void testJobKeyByGroup()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail job1group1 = buildJob("job1", "group1");
    storeJob(job1group1);
    JobDetail job1group2 = buildJob("job1", "group2");
    storeJob(job1group2);
    storeJob(buildJob("job2", "group2"));
    List<String> jobGroupNames = jobStore.getJobGroupNames();
    assertEquals(jobGroupNames.size(), 2);
    assertTrue(jobGroupNames.contains("group1"));
    assertTrue(jobGroupNames.contains("group2"));

    jobStore.removeJob(job1group1.getKey());

    jobGroupNames = jobStore.getJobGroupNames();
    assertEquals(jobGroupNames.size(), 1);
    assertTrue(jobGroupNames.contains("group2"));

    jobStore.removeJob(job1group2.getKey());

    jobGroupNames = jobStore.getJobGroupNames();
    assertEquals(jobGroupNames.size(), 1);
    assertTrue(jobGroupNames.contains("group2"));
  }

  @Test
  public void testGetTriggerGroupNames()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail storeJob = storeJob(buildJob("job"));
    storeTrigger(buildTrigger("trigger1", "group1", storeJob));
    storeTrigger(buildTrigger("trigger2", "group2", storeJob));
    List<String> triggerGroupNames = jobStore.getTriggerGroupNames();
    assertEquals(triggerGroupNames.size(), 2);
    assertTrue(triggerGroupNames.contains("group1"));
    assertTrue(triggerGroupNames.contains("group2"));
  }

  @Test
  public void testCalendarNames()
    throws JobPersistenceException {

    storeCalendar("cal1");
    storeCalendar("cal2");
    List<String> calendarNames = jobStore.getCalendarNames();
    assertEquals(calendarNames.size(), 2);
    assertTrue(calendarNames.contains("cal1"));
    assertTrue(calendarNames.contains("cal2"));
  }

  @Test
  public void storeJobAndTriggers()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    final Map<JobDetail, Set<? extends Trigger>> triggersAndJobs = Maps
        .newHashMap();

    final JobDetail job1 = buildJob();
    final Trigger trigger1 = buildTrigger(job1);
    final Set<Trigger> set1 = Sets.newHashSet();
    set1.add(trigger1);
    triggersAndJobs.put(job1, set1);

    final JobDetail job2 = buildJob();
    final Trigger trigger2 = buildTrigger(job2);
    final Set<Trigger> set2 = Sets.newHashSet();
    set2.add(trigger2);
    triggersAndJobs.put(job2, set2);

    jobStore.storeJobsAndTriggers(triggersAndJobs, false);

    final JobDetail retrieveJob1 = retrieveJob(job1.getKey().getName());
    assertNotNull(retrieveJob1);

    final JobDetail retrieveJob2 = retrieveJob(job2.getKey().getName());
    assertNotNull(retrieveJob2);

    final Trigger retrieveTrigger1 = retrieveTrigger(trigger1.getKey());
    assertNotNull(retrieveTrigger1);

    final Trigger retrieveTrigger2 = retrieveTrigger(trigger2.getKey());
    assertNotNull(retrieveTrigger2);
  }

  @Test(expectedExceptions = { ObjectAlreadyExistsException.class })
  public void storeJobAndTriggersThrowException()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    Map<JobDetail, Set<? extends Trigger>> triggersAndJobs = Maps.newHashMap();
    JobDetail job1 = buildJob();
    storeJob(job1);
    Trigger trigger1 = buildTrigger(job1);
    Set<Trigger> set1 = Sets.newHashSet();
    set1.add(trigger1);
    triggersAndJobs.put(job1, set1);
    jobStore.storeJobsAndTriggers(triggersAndJobs, false);
  }

  @Test
  public void testGetTriggersForJob()
    throws JobPersistenceException {

    JobDetail job = buildAndStoreJob();
    Trigger trigger1 = buildTrigger(job);
    Trigger trigger2 = buildTrigger(job);
    storeTrigger(trigger1);
    storeTrigger(trigger2);

    List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job.getKey());

    assertEquals(triggersForJob.size(), 2);
    assertTrue(triggersForJob.contains(trigger1));
    assertTrue(triggersForJob.contains(trigger2));
  }

  @Test
  public void testPauseTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildTrigger();
    storeTrigger(trigger);
    TriggerKey triggerKey = trigger.getKey();
    TriggerState triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.NORMAL);
    jobStore.pauseTrigger(triggerKey);
    triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.PAUSED);
  }

  @Test
  public void testResumeTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildTrigger();
    storeTrigger(trigger);
    TriggerKey triggerKey = trigger.getKey();
    TriggerState triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.NORMAL);
    jobStore.pauseTrigger(triggerKey);
    triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.PAUSED);

    jobStore.resumeTrigger(triggerKey);
    triggerState = jobStore.getTriggerState(triggerKey);
    assertEquals(triggerState, TriggerState.NORMAL);
  }

  @Test
  public void testPauseTriggers()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildAndStoreTrigger();
    Trigger trigger1 = buildAndStoreTrigger();
    Trigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
    storeTrigger(trigger2);
    assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.NORMAL);

    Collection<String> pauseTriggers = jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey()
        .getGroup()));

    assertEquals(pauseTriggers.size(), 1);
    assertTrue(pauseTriggers.contains(trigger.getKey().getGroup()));

    assertEquals(jobStore.getPausedTriggerGroups().size(), 1);
    assertTrue(jobStore.getPausedTriggerGroups().contains(trigger.getKey().getGroup()));

    Trigger trigger3 = buildAndStoreTrigger();

    assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger2.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()), TriggerState.PAUSED);
  }

  @Test
  public void testResumeTriggers()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildAndStoreTrigger();
    Trigger trigger1 = buildAndStoreTrigger();
    Trigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
    storeTrigger(trigger2);
    assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.NORMAL);

    Collection<String> pauseTriggers = jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey()
        .getGroup()));

    assertEquals(pauseTriggers.size(), 1);
    assertTrue(pauseTriggers.contains(trigger.getKey().getGroup()));

    Trigger trigger3 = buildAndStoreTrigger();

    assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger2.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()), TriggerState.PAUSED);

    Collection<String> resumeTriggers = jobStore.resumeTriggers(GroupMatcher.triggerGroupEquals(trigger.getKey()
        .getGroup()));

    assertEquals(resumeTriggers.size(), 1);
    assertTrue(resumeTriggers.contains(trigger.getKey().getGroup()));

    assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()), TriggerState.NORMAL);

    Trigger trigger4 = buildAndStoreTrigger();
    assertEquals(jobStore.getTriggerState(trigger4.getKey()), TriggerState.NORMAL);
  }

  @Test
  public void testResumeTriggerWithPausedJobs()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    JobDetail job1 = buildJob("job", "group3");
    storeJob(job1);
    Trigger trigger5 = buildTrigger(job1);
    storeTrigger(trigger5);

    assertEquals(jobStore.getTriggerState(trigger5.getKey()), TriggerState.NORMAL);
    jobStore.pauseJobs(GroupMatcher.jobGroupEquals("group3"));
    jobStore.resumeTriggers(GroupMatcher.triggerGroupEquals(trigger5.getKey().getGroup()));
    assertEquals(jobStore.getTriggerState(trigger5.getKey()), TriggerState.PAUSED);
  }

  @Test
  public void testPauseJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail jobDetail = buildAndStoreJob();
    Trigger trigger = buildTrigger(jobDetail);
    storeTrigger(trigger);
    TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
    assertEquals(triggerState, TriggerState.NORMAL);

    jobStore.pauseJob(jobDetail.getKey());

    triggerState = jobStore.getTriggerState(trigger.getKey());
    assertEquals(triggerState, TriggerState.PAUSED);
  }

  @Test
  public void testResumeJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail jobDetail = buildAndStoreJob();
    Trigger trigger = buildTrigger(jobDetail);
    storeTrigger(trigger);
    TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
    assertEquals(triggerState, TriggerState.NORMAL);

    jobStore.pauseJob(jobDetail.getKey());

    triggerState = jobStore.getTriggerState(trigger.getKey());
    assertEquals(triggerState, TriggerState.PAUSED);

    jobStore.resumeJob(jobDetail.getKey());

    triggerState = jobStore.getTriggerState(trigger.getKey());
    assertEquals(triggerState, TriggerState.NORMAL);
  }

  @Test
  public void testPauseJobs()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail job1 = buildAndStoreJobWithTrigger();
    JobDetail job2 = buildAndStoreJobWithTrigger();

    JobDetail job3 = buildJob("job3", "newgroup");
    storeJob(job3);
    storeTrigger(buildTrigger(job3));
    List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job1.getKey());
    triggersForJob.addAll(jobStore.getTriggersForJob(job2.getKey()));
    for (OperableTrigger trigger : triggersForJob) {
      assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.NORMAL);
    }

    Collection<String> pauseJobs = jobStore.pauseJobs(GroupMatcher.jobGroupEquals(job1.getKey().getGroup()));

    assertEquals(pauseJobs.size(), 1);
    assertTrue(pauseJobs.contains(job1.getKey().getGroup()));

    JobDetail job4 = buildAndStoreJobWithTrigger();

    triggersForJob = jobStore.getTriggersForJob(job1.getKey());
    triggersForJob.addAll(jobStore.getTriggersForJob(job2.getKey()));
    triggersForJob.addAll(jobStore.getTriggersForJob(job4.getKey()));
    for (OperableTrigger trigger : triggersForJob) {
      TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
      LOG.debug("State : [" + triggerState
          + "]Should be PAUSED for trigger : [" + trigger.getKey()
          + "] and job [" + trigger.getJobKey() + "]");
      assertEquals(triggerState, TriggerState.PAUSED);
      //          .overridingErrorMessage(
      //              "Should be PAUSED for trigger : [" + trigger.getKey()
      //                  + "] and job [" + trigger.getJobKey() + "]");
    }

    triggersForJob = jobStore.getTriggersForJob(job3.getKey());

    for (OperableTrigger trigger : triggersForJob) {
      assertEquals(jobStore.getTriggerState(trigger.getKey()), TriggerState.NORMAL);
    }
  }

  @Test
  public void testResumeJobs()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail job1 = buildAndStoreJobWithTrigger();
    JobDetail job2 = buildAndStoreJob();
    Trigger trigger2 = buildTrigger("trigger", "trigGroup2", job2);
    storeTrigger(trigger2);

    JobDetail job3 = buildJob("job3", "newgroup");
    storeJob(job3);
    storeTrigger(buildTrigger(job3));

    Collection<String> pauseJobs = jobStore.pauseJobs(GroupMatcher
        .anyJobGroup());

    assertEquals(pauseJobs.size(), 2);
    assertTrue(pauseJobs.contains(job1.getKey().getGroup()));
    assertTrue(pauseJobs.contains("newgroup"));

    List<OperableTrigger> triggersForJob = jobStore.getTriggersForJob(job1
        .getKey());

    for (OperableTrigger trigger : triggersForJob) {
      TriggerState triggerState = jobStore.getTriggerState(trigger.getKey());
      assertEquals(triggerState, TriggerState.PAUSED);
      //          .overridingErrorMessage(
      //              "Should be PAUSED for trigger : [" + trigger.getKey()
      //                  + "] and job [" + trigger.getJobKey() + "]");
    }

    triggersForJob = jobStore.getTriggersForJob(job3.getKey());
    for (OperableTrigger trigger : triggersForJob) {
      assertEquals(jobStore.getTriggerState(trigger.getKey()),
          TriggerState.PAUSED);
    }

    jobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("trigGroup2"));

    jobStore.resumeJobs(GroupMatcher.jobGroupEquals(job1.getKey().getGroup()));

    triggersForJob = jobStore.getTriggersForJob(job3.getKey());
    for (OperableTrigger trigger : triggersForJob) {
      assertEquals(jobStore.getTriggerState(trigger.getKey()),
          TriggerState.PAUSED);
    }

    triggersForJob = jobStore.getTriggersForJob(job1.getKey());
    for (OperableTrigger trigger : triggersForJob) {
      assertEquals(jobStore.getTriggerState(trigger.getKey()),
          TriggerState.NORMAL);
    }
  }

  @Test
  public void testPauseAll()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildAndStoreTrigger();
    Trigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
    storeTrigger(trigger2);
    assertEquals(jobStore.getTriggerState(trigger.getKey()),
        TriggerState.NORMAL);

    jobStore.pauseAll();

    assertEquals(jobStore.getPausedTriggerGroups().size(), 2);
    assertTrue(jobStore.getPausedTriggerGroups().contains(
        trigger.getKey().getGroup()));
    assertTrue(jobStore.getPausedTriggerGroups().contains(
        trigger2.getKey().getGroup()));

    Trigger trigger3 = buildAndStoreTrigger();

    assertEquals(jobStore.getTriggerState(trigger.getKey()),
        TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger2.getKey()),
        TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()),
        TriggerState.PAUSED);
  }

  @Test
  public void testResumeAll()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildAndStoreTrigger();
    Trigger trigger2 = buildTrigger("trigger2", "group2", buildAndStoreJob());
    storeTrigger(trigger2);
    assertEquals(jobStore.getTriggerState(trigger.getKey()),
        TriggerState.NORMAL);

    jobStore.pauseAll();

    assertEquals(jobStore.getPausedTriggerGroups().size(), 2);
    assertTrue(jobStore.getPausedTriggerGroups().contains(
        trigger.getKey().getGroup()));
    assertTrue(jobStore.getPausedTriggerGroups().contains(
        trigger2.getKey().getGroup()));

    Trigger trigger3 = buildAndStoreTrigger();

    assertEquals(jobStore.getTriggerState(trigger.getKey()),
        TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger2.getKey()),
        TriggerState.PAUSED);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()),
        TriggerState.PAUSED);

    jobStore.resumeAll();
    assertEquals(jobStore.getTriggerState(trigger.getKey()),
        TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger2.getKey()),
        TriggerState.NORMAL);
    assertEquals(jobStore.getTriggerState(trigger3.getKey()),
        TriggerState.NORMAL);
  }

  @Test
  public void testGetTriggerState()
    throws JobPersistenceException {

    TriggerState triggerState = jobStore.getTriggerState(new TriggerKey(
        "noname"));
    assertEquals(triggerState, TriggerState.NONE);

  }

  @Test
  public void testTriggersFired()
    throws Exception {

    Date baseFireTimeDate = DateBuilder.evenMinuteDateAfterNow();
    long baseFireTime = baseFireTimeDate.getTime();

    jobStore.storeJob(jobDetail, false);

    OperableTrigger trigger1 = new SimpleTriggerImpl("triggerFired1",
        "triggerFiredGroup", jobDetail.getKey().getName(), jobDetail.getKey().getGroup(),
        new Date(baseFireTime + 100), new Date(baseFireTime + 100), 2,
        2000);
    trigger1.computeFirstFireTime(null);

    jobStore.storeTrigger(trigger1, false);

    long firstFireTime = new Date(trigger1.getNextFireTime().getTime())
        .getTime();

    List<OperableTrigger> acquiredTriggers = jobStore.acquireNextTriggers(firstFireTime + 500, 1, 0L);
    assertEquals(1, acquiredTriggers.size());

    List<TriggerFiredResult> triggerFired = jobStore.triggersFired(acquiredTriggers);
    assertEquals(triggerFired.size(), 1);

    assertTrue(jobStore.checkExists(trigger1.getKey()));
    assertEquals(jobStore.getTriggerState(trigger1.getKey()), TriggerState.NORMAL);
    jobStore.removeTrigger(trigger1.getKey());
  }

  private HazelcastJobStore createJobStore(String name) {

    HazelcastJobStore hzJobStore = new HazelcastJobStore();
    hzJobStore.setInstanceName(name);
    HazelcastJobStore.setHazelcastClient(hazelcastInstance);
    return hzJobStore;
  }

  private JobDetail buildJob() {

    return buildJob("jobName" + buildJobIndex++, DEFAULT_GROUP);
  }

  private JobDetail buildJob(String jobName) {

    return buildJob(jobName, DEFAULT_GROUP);
  }

  private JobDetail buildJob(String jobName, String grouName) {

    JobDetail job = JobBuilder.newJob(Job.class)
        .withIdentity(jobName, grouName).build();
    return job;
  }

  private JobDetail storeJob(String jobName)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    return storeJob(buildJob(jobName));
  }

  private JobDetail storeJob(JobDetail jobDetail)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    this.jobStore.storeJob(jobDetail, false);
    return (JobDetail) jobDetail;
  }

  private JobDetail buildAndStoreJob()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    JobDetail buildJob = buildJob();
    this.jobStore.storeJob(buildJob, false);
    return (JobDetail) buildJob;
  }

  private JobDetail buildAndStoreJobWithTrigger()
    throws ObjectAlreadyExistsException, JobPersistenceException {

    JobDetail buildJob = buildJob();
    this.jobStore.storeJob(buildJob, false);
    Trigger trigger = buildTrigger(buildJob);
    storeTrigger(trigger);
    return (JobDetail) buildJob;
  }

  private JobDetail retrieveJob(String jobName)
    throws JobPersistenceException {

    return this.jobStore.retrieveJob(new JobKey(jobName, DEFAULT_GROUP));
  }

  /**
   * @return Trigger with default (and incremented) name and default group, and
   *         attached to a (already stored) job.
   */
  private Trigger buildTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    return buildTrigger("triggerName" + buildTriggerIndex++, DEFAULT_GROUP,
        buildAndStoreJob());
  }

  private Trigger buildTrigger(JobDetail jobDetail) {

    return buildTrigger("triggerName" + buildTriggerIndex++, DEFAULT_GROUP,
        jobDetail);
  }

  private Trigger buildAndStoreTrigger()
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    Trigger trigger = buildTrigger();
    storeTrigger(trigger);
    return trigger;
  }

  /**
   * @return build Trigger with specified name and group, unattached to a job.
   */
  private Trigger buildTrigger(String triggerName, String groupName) {

    SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule();
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerName, groupName).withSchedule(schedule).build();
    return (Trigger) trigger;
  }

  private Trigger buildTrigger(String triggerName, String groupName,
      JobDetail jobDetail) {

    SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule();
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerName, groupName).withSchedule(schedule)
        .forJob(jobDetail).build();
    ((OperableTrigger) trigger).computeFirstFireTime(null);
    return trigger;
  }

  private Trigger retrieveTrigger(TriggerKey triggerKey)
    throws JobPersistenceException {

    return jobStore.retrieveTrigger(triggerKey);
  }

  private void storeTrigger(Trigger trigger)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    jobStore.storeTrigger((OperableTrigger) trigger, false);
  }

  private void storeCalendar(String calName)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    jobStore.storeCalendar(calName, new BaseCalendar(), false, false);
  }

  private JobDetail createJob(String jobName, String jobGroup) {

    return JobBuilder.newJob(NoOpJob.class).withIdentity(jobName, jobGroup).build();
  }

  private OperableTrigger createTrigger(String triggerName, String triggerGroup, JobDetail job, long startAt) {

    return createTrigger(triggerName, triggerGroup, job, startAt, null);
  }

  private OperableTrigger createTrigger(String triggerName,
      String triggerGroup,
      JobDetail job,
      Long startAt,
      Long endAt) {

    return (OperableTrigger) TriggerBuilder
        .newTrigger()
        .withIdentity(new TriggerKey(triggerName, triggerGroup))
        .forJob(job)
        .startAt(new Date(startAt))
        .endAt(endAt != null ? new Date(endAt) : null)
        .build();
  }

  private OperableTrigger createAndComputeTrigger(String triggerName, String triggerGroup, JobDetail job, long startAt) {

    OperableTrigger trigger = createTrigger(triggerName, triggerGroup, job, startAt);
    trigger.computeFirstFireTime(null);
    return trigger;
  }

}
