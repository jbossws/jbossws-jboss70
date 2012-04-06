/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.webservices.tests;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.security.Constants.AUTHENTICATION;
import static org.jboss.as.security.Constants.CODE;
import static org.jboss.as.security.Constants.FLAG;
import static org.jboss.as.security.Constants.MODULE_OPTIONS;
import static org.jboss.as.security.Constants.SECURITY_DOMAIN;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.standalone.DeploymentAction;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlan;
import org.jboss.as.controller.client.helpers.standalone.DeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentActionResult;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentManager;
import org.jboss.as.controller.client.helpers.standalone.ServerDeploymentPlanResult;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.wsf.spi.deployer.Deployer;

/**
 * Remote deployer that uses AS7 client deployment API.
 *
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @author <a href="mailto:alessio.soldano@jboss.com">Alessio Soldano</a>
 */
public final class RemoteDeployer implements Deployer {

    private static final Logger LOGGER = Logger.getLogger(RemoteDeployer.class);
    private static final int PORT = 9999;
    private final Map<URL, String> url2Id = new HashMap<URL, String>();
    private final InetAddress address = InetAddress.getByName("127.0.0.1");
    private final Map<String, Integer> securityDomainUsers = new HashMap<String, Integer>(1);
    private final Map<String, Integer> archiveCounters = new HashMap<String, Integer>();

    private ServerDeploymentManager deploymentManager;

    public RemoteDeployer() throws IOException {
        deploymentManager = ServerDeploymentManager.Factory.create(address, PORT);
    }

    @Override
    public void deploy(final URL archiveURL) throws Exception {
        synchronized (archiveCounters) {
            String k = archiveURL.toString();
            if (archiveCounters.containsKey(k)) {
                int count = archiveCounters.get(k);
                archiveCounters.put(k, (count + 1));
                return;
            } else {
                archiveCounters.put(k, 1);
            }

            final DeploymentPlanBuilder builder = deploymentManager.newDeploymentPlan().add(archiveURL).andDeploy();
            final DeploymentPlan plan = builder.build();
            final DeploymentAction deployAction = builder.getLastAction();
            final String uniqueId = deployAction.getDeploymentUnitUniqueName();
            executeDeploymentPlan(plan, deployAction);
            url2Id.put(archiveURL, uniqueId);
        }
    }

    @Override
    public void undeploy(final URL archiveURL) throws Exception {
        synchronized (archiveCounters) {
            String k = archiveURL.toString();
            int count = archiveCounters.get(k);
            if (count > 1) {
                archiveCounters.put(k, (count - 1));
                return;
            } else {
                archiveCounters.remove(k);
            }

            final DeploymentPlanBuilder builder = deploymentManager.newDeploymentPlan();
            final String uniqueName = url2Id.get(archiveURL);
            if (uniqueName != null) {
                final DeploymentPlan plan = builder.undeploy(uniqueName).remove(uniqueName).build();
                final DeploymentAction deployAction = builder.getLastAction();
                try {
                    executeDeploymentPlan(plan, deployAction);
                } finally {
                    url2Id.remove(archiveURL);
                }
            }
        }
    }

    private void executeDeploymentPlan(final DeploymentPlan plan, final DeploymentAction deployAction) throws Exception {
        try {
            final ServerDeploymentPlanResult planResult = deploymentManager.execute(plan).get();

            if (deployAction != null) {
                final ServerDeploymentActionResult actionResult = planResult
                .getDeploymentActionResult(deployAction.getId());
                if (actionResult != null) {
                    final Exception deploymentException = (Exception) actionResult.getDeploymentException();
                    if (deploymentException != null)
                        throw deploymentException;
                }
            }
        } catch (final Exception e) {
            LOGGER.fatal(e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void addSecurityDomain(String name, Map<String, String> authenticationOptions) throws Exception {
        synchronized (securityDomainUsers) {
            if (securityDomainUsers.containsKey(name)) {
                int count = securityDomainUsers.get(name);
                securityDomainUsers.put(name, (count + 1));
                return;
            } else {
                securityDomainUsers.put(name, 1);
            }

            ModelControllerClient client = ModelControllerClient.Factory.create(address, PORT);
            ModelNode result = createSecurityDomain(client, name, authenticationOptions);
            checkResult(result);
        }
    }

    @Override
    public void removeSecurityDomain(String name) throws Exception {
        synchronized (securityDomainUsers) {
            int count = securityDomainUsers.get(name);
            if (count > 1) {
                securityDomainUsers.put(name, (count - 1));
                return;
            } else {
                securityDomainUsers.remove(name);
            }

            ModelControllerClient client = ModelControllerClient.Factory.create(address, PORT);
            ModelNode result = removeSecurityDomain(client, name);
            checkResult(result);
        }
    }

    private static ModelNode createSecurityDomain(ModelControllerClient client, String name, Map<String, String> authenticationOptions) throws IOException {
        ModelNode op = new ModelNode();
        op.get(OP).set(ADD);
        op.get(OP_ADDR).add(SUBSYSTEM, "security");
        op.get(OP_ADDR).add(SECURITY_DOMAIN, name);
        ModelNode loginModule = op.get(AUTHENTICATION).add();
        loginModule.get(CODE).set("UsersRoles");
        loginModule.get(FLAG).set("required");
        ModelNode moduleOptions = loginModule.get(MODULE_OPTIONS);
        if (authenticationOptions != null) {
            for (String k : authenticationOptions.keySet()) {
                moduleOptions.add(k, authenticationOptions.get(k));
            }
        }
        return client.execute(op);
    }

    private static ModelNode removeSecurityDomain(ModelControllerClient client, String name) throws IOException {
        ModelNode op = new ModelNode();
        op.get(OP).set(REMOVE);
        op.get(OP_ADDR).add(SUBSYSTEM, "security");
        op.get(OP_ADDR).add(SECURITY_DOMAIN, name);
        return client.execute(op);
    }

    private static void checkResult(ModelNode result) throws Exception {
        if (result.hasDefined("outcome") && "success".equals(result.get("outcome").asString())) {
            if (result.hasDefined("result")) {
                LOGGER.info(result.get("result"));
            }
        } else if (result.hasDefined("failure-description")) {
            throw new Exception(result.get("failure-description").toString());
        } else {
            throw new Exception("Operation not successful; outcome = " + result.get("outcome"));
        }
    }
}
