/*
 * This file is part of the Alitheia system, developed by the SQO-OSS
 * consortium as part of the IST FP6 SQO-OSS project, number 033331.
 *
 * Copyright 2008 - 2010 - Organization for Free and Open Source Software,  
 *                Athens, Greece.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package eu.sqooss.impl.service.metricactivator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.mortbay.log.Log;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import eu.sqooss.core.AlitheiaCore;
import eu.sqooss.service.abstractmetric.AbstractMetric;
import eu.sqooss.service.abstractmetric.AlitheiaPlugin;
import eu.sqooss.service.cluster.ClusterNodeActionException;
import eu.sqooss.service.cluster.ClusterNodeService;
import eu.sqooss.service.db.Bug;
import eu.sqooss.service.db.ClusterNodeProject;
import eu.sqooss.service.db.DAObject;
import eu.sqooss.service.db.DBService;
import eu.sqooss.service.db.Developer;
import eu.sqooss.service.db.InvocationRule;
import eu.sqooss.service.db.MailMessage;
import eu.sqooss.service.db.MailingList;
import eu.sqooss.service.db.MailingListThread;
import eu.sqooss.service.db.Metric;
import eu.sqooss.service.db.MetricType;
import eu.sqooss.service.db.Plugin;
import eu.sqooss.service.db.ProjectFile;
import eu.sqooss.service.db.ProjectFileState;
import eu.sqooss.service.db.ProjectVersion;
import eu.sqooss.service.db.StoredProject;
import eu.sqooss.service.db.InvocationRule.ActionType;
import eu.sqooss.service.db.InvocationRule.ScopeType;
import eu.sqooss.service.db.MetricType.Type;
import eu.sqooss.service.logging.Logger;
import eu.sqooss.service.metricactivator.MetricActivator;
import eu.sqooss.service.pa.PluginAdmin;
import eu.sqooss.service.pa.PluginInfo;
import eu.sqooss.service.scheduler.Job;
import eu.sqooss.service.scheduler.Scheduler;
import eu.sqooss.service.scheduler.SchedulerException;
import eu.sqooss.service.util.Pair;

public class MetricActivatorImpl  implements MetricActivator {

    /** The parent bundle's context object. */
    private BundleContext bc;

    private AlitheiaCore core;
    private Logger logger;
    private PluginAdmin pa;
    private DBService db;
    private Scheduler sched;
    private boolean fastSync = false;

    // Default action of the invocation rules chain
    private ActionType defaultAction = null;
    private Long defaultRuleId = null;
    private Long firstRuleId = null;
    private HashMap<Long,InvocationRule> rules = null;

    private HashMap<Class<? extends DAObject>, Integer> defaultPriority;
    private HashMap<Class<? extends DAObject>, Integer> maxPriority;
    private HashMap<Class<? extends DAObject>, Integer> currentPriorities;

    private HashMap<MetricType.Type, Class<? extends DAObject>> metricTypesToActivators;
    
    public MetricActivatorImpl() { }

    public void initRules() {
        // Load all defined invocation rules
        if (rules == null) {
            rules = new HashMap<Long, InvocationRule>();
            InvocationRule defaultRule = InvocationRule.getDefaultRule(db);
            defaultRuleId = defaultRule.getId();
            defaultAction = ActionType.fromString(defaultRule.getAction());
            InvocationRule rule = InvocationRule.first(db);
            firstRuleId = rule.getId();
            while (rule != null) {
                rules.put(rule.getId(), rule);
                rule = rule.next(db);
            }
        }
    }

    public void reloadRule (Long ruleId) {
        // Load the rules chain, if not done yet
        if(rules == null)
            initRules();

        // Invalid rule Id
        if (ruleId == null) return;
        // Retrieve the target rule from the database
        InvocationRule rule = db.findObjectById(
                InvocationRule.class, ruleId);
        // Rule update
        if (rule != null) {
            rules.put(rule.getId(), rule);
            // Check if this is the first rule in the chain
            if (rule.getPrevRule() == null) {
                firstRuleId = rule.getId();
            }
            // Check if this is the default rule in the chain
            if (rule.getId() == defaultRuleId.longValue()) {
                defaultAction = ActionType.fromString(rule.getAction());
            }
        }
        // Rule remove
        else {
            if (rules.containsKey(ruleId))
                rules.remove(ruleId);
        }
    }

    public ActionType matchRules (AlitheiaPlugin ap, DAObject resource) {
        // Load the rules chain, if not done yet
        if (rules == null)
            initRules();

        // Retrieve the first rule
        InvocationRule rule = rules.get(firstRuleId);

        // Retrieve the plug-in DAO
        Plugin plugin = null;
        if ((ap != null) && (ap.getUniqueKey() != null)) {
            HashMap<String, Object> properties =
                new HashMap<String, Object>();
            List<Plugin> plugins =
                db.findObjectsByProperties(Plugin.class, properties);
            if ((plugins != null) && (plugins.size() > 0)) {
                plugin = plugins.get(0);
            }
        }
        //====================================================================
        // Match against a resource of type ProjectFile
        //====================================================================
        if (resource instanceof ProjectFile) {
            // Skip on project file in state "DELETED"
            if (((ProjectFile) resource).getState().equals(ProjectFileState.deleted())) {
                return defaultAction;
            }
            ProjectVersion version =
                ((ProjectFile) resource).getProjectVersion();
            StoredProject project = version.getProject();
            // Check for an invalid resource parameters
            if ((project == null)
                    || (plugin == null)
                    || (version == null)) {
                return defaultAction;
            }
            // Traverse through each rule until a match is found.
            while (rule != null) {
                // Check for the default rule
                if (rule.getId() == defaultRuleId) {
                    return defaultAction;
                }
                // Retrieve the rule's metric type
                Type metricType = null;
                if (rule.getMetricType() != null) {
                    metricType = (db.attachObjectToDBSession(
                            rule.getMetricType())).getEnumType();
                }
                // Skip on a metric type which can not evaluate that resource
                if ((metricType != null)
                        && (metricType != Type.SOURCE_FILE)
                        && (metricType != Type.SOURCE_DIRECTORY)
                        && (metricType != Type.PROJECT_VERSION)){
                    rule = rules.get(rule.getNextRule());
                    continue;
                }
                // Skip on a different project Id
                if ((rule.getProject() != null)
                        && (rule.getProject().getId() != project.getId())) {
                    rule = rules.get(rule.getNextRule());
                    continue;
                }
                // Skip on a different plug-in Id
                if ((rule.getPlugin() != null)
                        && (rule.getPlugin().getId() != plugin.getId())) {
                    rule = rules.get(rule.getNextRule());
                    continue;
                }
                // Skip on a different metric type
                boolean metricTypeNotFound = true;
                for (Metric metric : ap.getAllSupportedMetrics()) {
                    metric = db.attachObjectToDBSession(metric);
                    if (metric.getMetricType().getEnumType() == metricType) {
                        metricTypeNotFound = false;
                    }
                }
                if ((metricType != null) && (metricTypeNotFound)) {
                    rule = rules.get(rule.getNextRule());
                    continue;
                }
                // Match against the current rule
                if (rule.match(
                        ScopeType.fromString(rule.getScope()),
                        rule.getValue(),
                        (ProjectFile) resource)) {
                    // TODO: Remove the debug to speed up processing
                    logger.debug("Rule match: "
                            + ((ProjectFile) resource).getFileName()
                            + " : " + rule.getAction());
                    return ActionType.fromString(rule.getAction());
                }
                // Move to the next rule
                rule = rules.get(rule.getNextRule());
            }
        }
        // No matching rule found. Return the default action.
        return defaultAction;
    }

    @Override
	public <T extends DAObject> void runMetric(T resource, AlitheiaPlugin ap) {
    	Class<? extends DAObject> activator = resource.getClass();
    	Job j = new MetricActivatorJob((AbstractMetric)ap, resource.getId(), logger, 
    			metricTypesToActivators.get(activator),
    			getNextPriority(metricTypesToActivators.get(activator)),
    			fastSync);
    	try {
            sched.enqueue(j);
        } catch (SchedulerException e) {
            logger.error("Could not start metric scheduler job");
        }
	}
    
    /**{@inheritDoc}*/
    @Override
    public void syncMetrics(StoredProject sp, Class<? extends DAObject> actType) {
        //TODO: Check if the project is assigned to this host
        
        List<PluginInfo> plugins = pa.listPluginProviders(actType);
        
        if (plugins == null || plugins.size() == 0) {
            logger.warn("No metrics found for activation type " 
                    + actType.getName());
            return;
        }
        
        /* Fire up plug-ins */
        for (PluginInfo pi : plugins) {
           AbstractMetric m = (AbstractMetric) bc.getService(pi.getServiceRef());
           try {
               sched.enqueue(new MetricSchedulerJob(m, sp));
           } catch (SchedulerException e) {
               logger.error("Could not start metric scheduler job");
           }
        }
    }

    /**{@inheritDoc}*/
    @SuppressWarnings("unchecked")
    @Override
    public void syncMetrics(AlitheiaPlugin ap) {
        List<StoredProject> lp = 
            (List<StoredProject>) db.doHQL("from StoredProject");
        
        for(StoredProject sp : lp) {
            syncMetric(ap, sp);
        }
    }
    
    /**{@inheritDoc}*/
    @Override
    public void syncMetrics(StoredProject sp) {
        Collection<PluginInfo> plugins = pa.listPlugins();
        
        for(PluginInfo p : plugins) {
            AlitheiaPlugin ap = 
                (AlitheiaPlugin) bc.getService(p.getServiceRef());
            syncMetric(ap, sp);
        }
    }
 
    /**{@inheritDoc}*/
    @Override
    public void syncMetric(AlitheiaPlugin m, StoredProject sp) {
        if (!canRunOnHost(sp))
            return;
        Set<AlitheiaPlugin> deps = new HashSet<AlitheiaPlugin>();
        deps.add(m);
        
        for (String s: m.getDependencies()) {
        	deps.add(pa.getImplementingPlugin(s));
        }
        
        List<AlitheiaPlugin> toExec = getExcecutionOrder(deps);
        
        try {
        	Collections.reverse(toExec);
        	List<Job> jobs = new ArrayList<Job>();
        	Job old = null;
        	for (AlitheiaPlugin a : toExec) {
        		Job j = new MetricSchedulerJob(m, sp);
        		if (old != null) {
        			old.dependsOn(j);
        			old = j;
        		}
        	}
        	for (Job j : jobs) {
        		sched.enqueue(j);
        	}
        } catch (SchedulerException e) {
            logger.error("Could not start metric scheduler job");
        }
    }

    private boolean canRunOnHost(StoredProject sp) {
        ClusterNodeService cns = null;
        ClusterNodeProject cnp = null;
        
        cns = core.getClusterNodeService();
        if (cns == null) {
            logger.warn("ClusterNodeService reference not found " +
            		"- ClusterNode assignment checks will be ignored");
            return true;
        } else {
            cnp = ClusterNodeProject.getProjectAssignment(sp);
            if (cnp == null) {
                // project is not assigned yet to any ClusterNode, assign it
                // here by-default
                try {
                    cns.assignProject(sp);
                } catch (ClusterNodeActionException ex) {
                    logger.warn("Couldn't assign project " + sp.getName()
                            + " to ClusterNode " + cns.getClusterNodeName());
                    return false;
                }
            } else {
                // project is somewhere assigned , check if it is assigned to
                // this Cluster Node
                if (!cns.isProjectAssigned(sp)) {
                    logger.warn("Project " + sp.getName() + " is not assigned" +
                        " to this ClusterNode - Ignoring Metric synchronization");
                    return false;
                }
            }
        }
        // Done with ClusterNode Checks
        return true;
    }
    
    private synchronized int getNextPriority(Class<? extends DAObject> actType) {
        Integer priority = currentPriorities.get(actType);
        
        if (priority == null) {
            priority = defaultPriority.get(actType);
            currentPriorities.put(actType, priority);
            return priority;
        }
        
        priority = priority + 1;
        
        if (priority >= maxPriority.get(actType)) {
            priority = defaultPriority.get(actType);
        }
        
        currentPriorities.put(actType, priority);
        
        return priority;
    }
    
    private List<AlitheiaPlugin> getExcecutionOrder(Set<AlitheiaPlugin> unordered) {
    	Map<AlitheiaPlugin, Integer> idx = new HashMap<AlitheiaPlugin, Integer>();
    	Map<Integer, AlitheiaPlugin> invidx = new HashMap<Integer, AlitheiaPlugin>();
    	
    	GraphTS graph = new GraphTS();
    	
    	//Build the adjacency matrix
    	for (AlitheiaPlugin p : unordered) {
    		if (!idx.containsKey(p)) {
        		int n = graph.addVertex(p);
	    		idx.put(p, n);
	    		invidx.put(n, p);
	    	}
    		
    	    Set<String> deps = p.getDependencies();
    	    for (String metric : deps) {
    	    	AlitheiaPlugin dep = pa.getImplementingPlugin(metric);
    	    	if (!idx.containsKey(dep)) {
    	    		int n = graph.addVertex(p);
    	    		idx.put(dep, n);
    	    		invidx.put(n, dep);
    	    	}
    	    	graph.addEdge(idx.get(p), idx.get(dep));
    	    }
    	}
    	
    	AlitheiaPlugin[] sorted = graph.topo();
    	/*
    	logger.debug("Calculated adjacency matrix:");
    	for (int i = 0; i < mtxCounter; i++) {
    		logger.debug("  " + invidx.get(adjMatrix[i][0]) + "->" + 
    				invidx.get(adjMatrix[i][1]));
    	}
    	
    	//Get nodes with no outgoing links (no dependencies)
    	boolean found = false;
    	int[] nodepstmp = new int[invidx.keySet().size()];
    	int nodepsct = 0;
    	for (int j : invidx.keySet()) {
    		for (int i = 0; i < mtxCounter; i++) {
    			if (adjMatrix[i][0] == j) {
    				found = true;
    				break;
    			}
    		}
    		if (!found) {
    			nodepstmp[nodepsct] = j;
    			nodepsct++;
    		}
    		found = false;
    	}
    	
    	//Init topological sort arrays
    	Set<Integer> nodeps = new HashSet<Integer>();
    	System.arraycopy(nodepstmp, 0, nodeps, 0, nodepsct);
    	List<Integer> sorted = new ArrayList<Integer>();
    	
    	while (!nodeps.isEmpty()) {
    		//int i = nodeps.
    	}*/
    	
    	return Collections.EMPTY_LIST;
    }
    
    /**
     * Job that creates metric jobs. Used to avoid blocking the UI or user
     * scipts while scheduling large metric updates. Its priority ensures
     * that it will not fill up queues while updater jobs are running, 
     * leaving memory free till it is really required. 
     */
    private class MetricSchedulerJob extends Job {

        private AlitheiaPlugin m;
        private StoredProject sp;
        
        public MetricSchedulerJob(AlitheiaPlugin m, StoredProject sp) {
            this.m = m;
            this.sp = sp;
        }
        
        @Override
        public int priority() {
            return 0x2;
        }

        @Override
        protected void run() throws Exception {
            DBService dbs = AlitheiaCore.getInstance().getDBService();
            dbs.startDBSession();
            sp = DAObject.loadDAObyId(sp.getId(), StoredProject.class);
            PluginInfo mi = pa.getPluginInfo(m);
            Set<Class<? extends DAObject>> actTypes = mi.getActivationTypes();
            
            if ((actTypes == null) || actTypes.isEmpty()) {
                logger.error("Plugin " + mi.getPluginName() +
                            " has no activation types");
                return;
            }

            List<Metric> metrics = pa.getPlugin(mi).getAllSupportedMetrics();
            
            Map<MetricType.Type, TreeSet<Long>> objectIds = new HashMap<MetricType.Type, TreeSet<Long>>();
            MetricType.Type activationType = null;
            
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("project", sp);
            
            for (Metric m : metrics) {
            	StringBuffer q = new StringBuffer();
            	if (m.getMetricType().equals(MetricType.getMetricType(Type.PROJECT_VERSION))) {
            		q.append("select pv.id ") 
            		.append("from ProjectVersion pv ")
            		.append("where pv.project = :project ") 
            		.append("and not exists( ")
            		.append("select pvm.projectVersion ")
            		.append("from ProjectVersionMeasurement pvm ")
            		.append("where pvm.projectVersion.id = pv.id and pvm.metric.id = :metric) ") 
            		.append("order by pv.sequence asc");
            		activationType = Type.PROJECT_VERSION;
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.SOURCE_FILE))) {
            		q.append("select pf.id") 
            		.append(" from ProjectVersion pv, ProjectFile pf")
            		.append(" where pf.projectVersion=pv and pv.project = :project") 
            		.append(" and not exists (")
            		.append("  select pfm.projectFile ")
            		.append("  from ProjectFileMeasurement pfm") 
            		.append("  where pfm.projectFile.id = pf.id and pfm.metric.id = :metric)")
            		.append(" and pf.isDirectory = false")  
            		.append(" order by pv.sequence asc" );
            		activationType = Type.SOURCE_FILE;
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.SOURCE_DIRECTORY))) {
            		q.append("select pf.id") 
            		.append(" from ProjectVersion pv, ProjectFile pf")
            		.append(" where pf.projectVersion=pv and pv.project = :project") 
            		.append(" and not exists (")
            		.append("  select pfm.projectFile ")
            		.append("  from ProjectFileMeasurement pfm") 
            		.append("  where pfm.projectFile.id = pf.id and pfm.metric.id = :metric)")
            		.append(" and pf.isDirectory = true")  
            		.append(" order by pv.sequence asc" );
            		activationType = Type.SOURCE_DIRECTORY;
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.MAILING_LIST))) {
            		throw new Exception("Metric synchronisation with MAILING_LIST objects not implemented");
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.MAILMESSAGE))) {
            		q.append("select mm.id")
            		.append(" from MailMessage mm ")
            		.append(" where mm.list.storedProject = :project ")
            		.append(" and mm.id not in (")
					.append(" select mmm.mail.id ")
					.append(" from MailMessageMeasurement mmm")
					.append(" where mmm.metric.id =:metric")
					.append(" and mmm.mail.id = mm.id))");
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.MAILTHREAD))) {
            		q.append("select mlt.id ")
            		.append("from MailingListThread mlt ") 
            		.append("where mlt.list.storedProject = :project ") 
            		.append("and mlt.id not in ( ")
            		.append("select mltm.thread.id ")
            		.append("from MailingListThreadMeasurement mltm ")
            		.append("where mltm.metric.id =:metric ")
            		.append("and mltm.thread.id = mlt.id)" );
            	} else if (m.getMetricType().equals(MetricType.getMetricType(Type.BUG))) {
            		throw new Exception("Metric synchronisation with BUG objects not implemented");
            	} else {
            		throw new Exception("Metric synchronisation with GENERIC objects not implemented");
            	}
                params.put("metric", m.getId());
                List<Long> objects = (List<Long>) db.doHQL(q.toString(), params);
                
                if (objectIds.get(activationType) == null) {
                	objectIds.put(activationType, new TreeSet<Long>());	
                }
                
                objectIds.get(activationType).addAll(objects);
            }
            
            AbstractMetric metric = 
                (AbstractMetric) bc.getService(mi.getServiceRef());
            HashSet<Job> jobs = new HashSet<Job>();
            
            for (MetricType.Type actType : objectIds.keySet()) {
            	for (Long l : objectIds.get(actType)) {
            		jobs.add(new MetricActivatorJob(metric, l, logger, 
            			metricTypesToActivators.get(actType),
            			getNextPriority(metricTypesToActivators.get(actType)),
            			fastSync));
            	}
            }
            sched.enqueueNoDependencies(jobs);
            dbs.commitDBSession();
        }
        
        @Override
        public String toString() {
            return "MetricSchedulerJob - Project:{" + sp + "}" ;
        }
    }

	@Override
	public void setInitParams(BundleContext bc, Logger l) {
		this.bc = bc;
		this.logger = l;
		
		defaultPriority = new HashMap<Class<? extends DAObject>, Integer>();
        defaultPriority.put(ProjectFile.class, 0x1000000);
        defaultPriority.put(MailMessage.class, 0x1000000);
        defaultPriority.put(Bug.class, 0x1000000);
        defaultPriority.put(ProjectVersion.class, 0x8000000);
        defaultPriority.put(MailingList.class, 0x8000000);
        defaultPriority.put(Developer.class, 0xf000000);
        defaultPriority.put(MailingListThread.class, 0xf000000);
        defaultPriority.put(StoredProject.class, 0x1E000000);
        
        maxPriority = new HashMap<Class<? extends DAObject>, Integer>();
        maxPriority.put(ProjectFile.class, defaultPriority.get(ProjectVersion.class));
        maxPriority.put(MailMessage.class, defaultPriority.get(MailingList.class));
        maxPriority.put(Bug.class, defaultPriority.get(Developer.class));
        maxPriority.put(ProjectVersion.class, defaultPriority.get(StoredProject.class));
        maxPriority.put(MailingList.class, defaultPriority.get(StoredProject.class));
        maxPriority.put(Developer.class, defaultPriority.get(StoredProject.class));
        maxPriority.put(MailingListThread.class, defaultPriority.get(StoredProject.class));
        maxPriority.put(StoredProject.class, 0x1E000000);
        
        metricTypesToActivators = new HashMap<Type, Class<? extends DAObject>>();
        metricTypesToActivators.put(Type.SOURCE_DIRECTORY, ProjectFile.class);
        metricTypesToActivators.put(Type.SOURCE_FILE, ProjectFile.class);
        metricTypesToActivators.put(Type.BUG, Bug.class);
        metricTypesToActivators.put(Type.PROJECT_VERSION, ProjectVersion.class);
        metricTypesToActivators.put(Type.MAILING_LIST, MailingList.class);
        metricTypesToActivators.put(Type.MAILMESSAGE, MailMessage.class);
        metricTypesToActivators.put(Type.MAILTHREAD, MailingListThread.class);
	}

	@Override
	public void shutDown() {
	}

	@Override
	public boolean startUp() {
	    ServiceReference serviceRef = null;
        serviceRef = bc.getServiceReference(AlitheiaCore.class.getName());
        core = (AlitheiaCore) bc.getService(serviceRef);

        currentPriorities = new HashMap<Class<? extends DAObject>, Integer>();
        
        this.pa = core.getPluginAdmin();
        this.db = core.getDBService();
        this.sched = core.getScheduler();
        
        String sync = bc.getProperty("eu.sqooss.metricactivator.sync");
        
        if (sync != null && sync.equalsIgnoreCase("fast"))
            this.fastSync = true;
	
        return true;
	}
}

//vi: ai nosi sw=4 ts=4 expandtab