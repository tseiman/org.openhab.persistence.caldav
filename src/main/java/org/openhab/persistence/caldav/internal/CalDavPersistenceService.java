/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.persistence.caldav.internal;



import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.repeatSecondlyForever;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.impl.matchers.GroupMatcher.jobGroupEquals;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Dictionary;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.DateTime;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.lang.StringUtils;
import org.openhab.core.items.Item;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.io.caldav.util.EasySSLProtocolSocketFactory;
import org.osaf.caldav4j.CalDAVCollection;
import org.osaf.caldav4j.CalDAVConstants;
import org.osaf.caldav4j.exceptions.CalDAV4JException;
import org.osaf.caldav4j.methods.CalDAV4JMethodFactory;
import org.osaf.caldav4j.methods.HttpClient;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * This implementation of the {@link PersistenceService} provides Presence 
 * Simulation features based on the CalDav Calendar Service.
 * 
 * this code is derived from GCal implementation written by Thomas.Eichstaedt-Engelen
 * @author Thomas.Schmidt
 * @since 1.8.0
 */
public class CalDavPersistenceService implements PersistenceService, ManagedService {

	private static final Logger logger =LoggerFactory.getLogger(CalDavPersistenceService.class);

	private static final String CALDAV_SCHEDULER_GROUP = "CalDavCalendarPersistence";

	/** the upload interval (optional, defaults to 10 seconds) */
	private static int uploadInterval = 10;


	private static String host = "";
	private static int port = 0;
	private static String url = "";
	private static String username = "";
	private static String password = "";
	private static boolean tls = true;
	private static boolean strictTls = true;


	/** the offset (in days) which will used to store future events */
	private static int offset = 14;

	/**
	 * the base script which is written to the newly created Calendar-Events by
	 * the CalDav based presence simulation. It must contain two format markers
	 * <code>%s</code>. The first marker represents the Item to send the command
	 * to and the second represents the State.
	 */
	private static String executeScript = "> if (PresenceSimulation.state == ON) %s.sendCommand(%s)";

	/** indicated whether this service was properly initialized */ 
	private static boolean initialized = false;

	/** holds the CalDav Calendar entries to upload to CalDav */
	private static Queue<VEvent> entries = new ConcurrentLinkedQueue<VEvent>();


	public void activate() {
		scheduleUploadJob();
	}

	public void deactivate() {
		cancelAllJobs();
	}


	/**
	 * @{inheritDoc}
	 */
	public String getName() {
		return "caldav-persistence";
	}

	/**
	 * @{inheritDoc}
	 */
	public void store(Item item) {
		store(item, item.getName());
	}

	/**
	 * Creates a new CalDav Calendar Entry for each <code>item</code> and adds
	 * it to the processing queue. The entries' title will either be the items
	 * name or <code>alias</code> if it is <code>!= null</code>.
	 * 
	 * The new Calendar Entry will contain a single command to be executed e.g.<br>
	 * <p><code>send &lt;item.name&gt; &lt;item.state&gt;</code></p>
	 * 
	 * @param item the item which state should be persisted.
	 * @param alias the alias under which the item should be persisted.
	 */

	public void store(final Item item, final String alias) {

		if (initialized) {
			String newAlias = alias != null ? alias : item.getName();

			TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
			TimeZone timeZone=registry.getTimeZone(TimeZone.getDefault().getID());
			// DateTime dateTime = new DateTime();
			Calendar cal=Calendar.getInstance(timeZone);

			cal.add(Calendar.DAY_OF_MONTH, offset); 
			
			DateTime startDt = new DateTime(cal.getTimeInMillis());
			startDt.setTimeZone(timeZone);

			VEvent event = new VEvent(startDt,"[PresenceSimulation] " + newAlias);
			event.getProperties().add(new Description(String.format(executeScript, item.getName(), item.getState().toString())));
			entries.offer(event);
			logger.debug("added new CalDav entry '{}' for item '{}' to upload queue", event.getSummary(), item.getName());
		} else {
			logger.warn("CalDav Persistence is not initialized propperly to upload events");
		}




	}

	/**
	 * Schedules new quartz scheduler job for uploading calendar entries to CalDav
	 */
	private void scheduleUploadJob() {

		try {
			Scheduler sched = StdSchedulerFactory.getDefaultScheduler();



			JobDetail job = newJob(UploadJob.class)
					.withIdentity("Upload_CalDav-Entries", CALDAV_SCHEDULER_GROUP)
					.build();

			SimpleTrigger trigger = newTrigger()
					.withIdentity("Upload_CalDav-Entries", CALDAV_SCHEDULER_GROUP)
					.withSchedule(repeatSecondlyForever(uploadInterval))
					.build();

			sched.scheduleJob(job, trigger);
			logger.debug("Scheduled CalDav Calendar Upload-Job with interval '{}'", uploadInterval);
		} catch (SchedulerException e) {
			logger.warn("Could not create CalDav Calendar Upload-Job: {}", e.getMessage());
		}		
	}

	/**
	 * Delete all quartz scheduler jobs of the group <code>CalDavCalendarPersistence</code>.
	 */
	private void cancelAllJobs() {
		try {
			Scheduler sched = StdSchedulerFactory.getDefaultScheduler();
			Set<JobKey> jobKeys = sched.getJobKeys(jobGroupEquals(CALDAV_SCHEDULER_GROUP));
			if (jobKeys.size() > 0) {
				sched.deleteJobs(new ArrayList<JobKey>(jobKeys));
				logger.debug("Found {} CalDav Calendar Upload-Jobs to delete from DefaulScheduler (keys={})", jobKeys.size(), jobKeys);
			}
		} catch (SchedulerException e) {
			logger.warn("Couldn't remove CalDav Calendar Upload-Job: {}", e.getMessage());
		}		
	}


	/**
	 * A quartz scheduler job to upload {@link VEvent}s to
	 * the remote Calendar. There can be only one instance of a specific job 
	 * type running at the same time.
	 * 
	 * @author Thomas.Schmidt
	 */

	@DisallowConcurrentExecution
	public static class UploadJob implements Job {

		@Override
		public void execute(JobExecutionContext context) throws JobExecutionException {
			logger.trace("going to upload {} calendar entries to CalDav now ...", entries.size());
			for (VEvent entry : entries) {
				upload(entry);
				entries.remove(entry);
			}  
		}


		private void upload(VEvent entry) {
			try {
				long startTime = System.currentTimeMillis();
				createCalendarEvent(host, port, url, username, password, entry,tls,strictTls);
				logger.debug("succesfully created new calendar event (title='{}', date='{}', content='{}') in {}ms",
						new Object[] { entry.getSummary(),	entry.getStartDate(), entry.getDescription(), System.currentTimeMillis() - startTime}); 
			} catch (CalDAV4JException ae) {
				logger.error("failed to add CalDav entry: {}", ae.getMessage());
			}
		}

		/**
		 * Creates a new calendar entry.
		 * @throws CalDAV4JException 
		 * 
		 */
		private void createCalendarEvent( String host, int port, String url, String username, String password,VEvent event, boolean useTls, boolean strictTlsCheck) throws CalDAV4JException  {

			if(useTls && (! strictTlsCheck)) {
				ProtocolSocketFactory socketFactory =   new EasySSLProtocolSocketFactory( );
				Protocol https = new Protocol( "https", socketFactory, port);
				Protocol.registerProtocol( "https", https );
			}
			HttpClient httpClient = new HttpClient();
			httpClient.getHostConfiguration().setHost(host, port, useTls ? "https" : "http");


			UsernamePasswordCredentials httpCredentials = new UsernamePasswordCredentials(username, password);
			httpClient.getState().setCredentials(AuthScope.ANY, httpCredentials);
			httpClient.getParams().setAuthenticationPreemptive(true);


			TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
			VTimeZone vtimeZone=registry.getTimeZone(TimeZone.getDefault().getID()).getVTimeZone();

			CalDAVCollection collection = new CalDAVCollection(
					url,
					(HostConfiguration) httpClient.getHostConfiguration().clone(),
					new CalDAV4JMethodFactory(),
					CalDAVConstants.PROC_ID_DEFAULT
					);

			collection.add(httpClient, event, vtimeZone);
		}	

	}



	@Override
	public void updated(Dictionary<String, ?> config) throws ConfigurationException {


		if (config == null) {
			logger.error("no configuration set for caldav-persistence");
			return;
		}


		String usernameString = (String) config.get("username");
		username = usernameString;
		if (StringUtils.isBlank(username)) {
			throw new ConfigurationException("caldav-persistence:username", "username must not be blank - please configure an aproppriate username in openhab.cfg");
		}
		logger.trace("username: {}", username);

		String passwordString = (String) config.get("password");
		password = passwordString;
		if (StringUtils.isBlank(password)) {
			throw new ConfigurationException("caldav-persistence:password", "password must not be blank - please configure an aproppriate password in openhab.cfg");
		}
		logger.trace("password: {}", password);

		String hostString = (String) config.get("host");
		host = hostString;
		if (StringUtils.isBlank(host)) {
			throw new ConfigurationException("caldav-persistence:host", "host must not be blank - please configure an aproppriate host in openhab.cfg");
		}
		logger.trace("host: {}", host);

		String tlsString = (String) config.get("tls");
		if (StringUtils.isNotBlank(tlsString)) {
			try {
				tls = Boolean.parseBoolean(tlsString);
			}
			catch (IllegalArgumentException iae) {
				logger.warn("couldn't parse caldav-persistence:tls '{}' to a boolean",tlsString);
			}
		} else {
			tls = true;
		}
		logger.trace("tls: {}", tls);


		String strictTlsString = (String) config.get("strict-tls");
		if (StringUtils.isNotBlank(strictTlsString)) {
			try {
				strictTls = Boolean.parseBoolean(strictTlsString);
			}
			catch (IllegalArgumentException iae) {
				logger.warn("couldn't parse caldav-persistence:strict-tls '{}' to a boolean",strictTlsString);
			}
		} else {
			strictTls = true;
		}
		logger.trace("strict-tls: {}", strictTls);

		if(!tls) {
			logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
			logger.warn("!!  You have disabled tls/ssl for CalDav-Persistence. Calendar data is exchanged unencrypted. !!");
			logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		}

		if(!strictTls && tls) {
			logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
			logger.warn("!!  You have disabled strict certificate checking by setting strict-tls to false.    !!");
			logger.warn("!!  Actually all checking for certificates in CalDav-Persistence is disabled now     !!");
			logger.warn("!!  - which means that there is no real security - as you accept any certificate,    !!");
			logger.warn("!!  even those which might be injected for Man-In The Middle-Attacks - try to        !!");
			logger.warn("!!  Register your certificate to your java certificate store and set strict-tls to   !!");
			logger.warn("!!  true. Disable the tls checking is just meant for debugging purposes.             !!");
			logger.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		}
		String portString = (String) config.get("port");
		if (StringUtils.isNotBlank(portString)) {
			try {
				port = Integer.valueOf(portString);
			}
			catch (IllegalArgumentException iae) {
				logger.warn("couldn't parse caldav-persistence:port '{}' to an integer",portString);
			}
		} else {
			if(tls) {
				port = 443;
			} else {
				port = 80;
			}
		}

		logger.trace("port: {}", port);


		String urlString = (String) config.get("url");
		url = urlString;
		if (StringUtils.isBlank(url)) {
			throw new ConfigurationException("caldav-persistence:url", "url must not be blank - please configure an aproppriate url in openhab.cfg");
		}
		logger.trace("url: {}", url);

		String offsetString = (String) config.get("offset");
		if (StringUtils.isNotBlank(offsetString)) {
			try {
				offset = Integer.valueOf(offsetString);
			}
			catch (IllegalArgumentException iae) {
				logger.warn("couldn't parse caldav-persistence:offset '{}' to an integer",offsetString);
			}
		}
		logger.trace("offset: {}", offset);

		String uploadIntervalString = (String) config.get("upload-interval");
		if (StringUtils.isNotBlank(uploadIntervalString)) {
			try {
				uploadInterval = Integer.valueOf(uploadIntervalString);
			}
			catch (IllegalArgumentException iae) {
				logger.warn("couldn't parse caldav-persistence:upload-interval '{}' to an integer",uploadIntervalString);
			}
		}
		logger.trace("upload-interval: {}", offset);


		String executeScriptString = (String) config.get("executescript");
		if (StringUtils.isNotBlank(executeScriptString)) {
			executeScript = executeScriptString;
		}
		logger.trace("executescript: {}", executeScript);

		initialized = true;

		logger.info("Initialized CalDav persistence service successfully");


	}

}

