package org.ngrinder.perftest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * Created by root on 15-6-19.
 */

public class DynamicAgentServletContextLinster implements ServletContextListener{

    private static final Logger LOG = LoggerFactory.getLogger(DynamicAgentServletContextLinster.class);

    /**
     * * Notification that the web application initialization
     * * process is starting.
     * * All ServletContextListeners are notified of context
     * * initialization before any filter or servlet in the web
     * * application is initialized.
     *
     * @param sce
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOG.info("ServletContex initialized....");
        String serverInfo = sce.getServletContext().getServerInfo();
        System.setProperty("controller-server-info", serverInfo);
        LOG.info(serverInfo);
    }

    /**
     * * Notification that the servlet context is about to be shut down.
     * * All servlets and filters have been destroy()ed before any
     * * ServletContextListeners are notified of context
     * * destruction.
     *
     * @param sce
     */
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.info("ServletContex destroyed....");
    }


}
