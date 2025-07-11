/*
 * Copyright (c) 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.rest.service;

import gnu.inet.encoding.Stringprep;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jivesoftware.openfire.SharedGroupException;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.plugin.rest.RESTServicePlugin;
import org.jivesoftware.openfire.plugin.rest.controller.UserServiceLegacyController;
import org.jivesoftware.openfire.user.UserAlreadyExistsException;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.PrintWriter;

@Path("restapi/v1")
@Tag(name = "UserService (deprecated)", description = "Undocumented UserService endpoint, retained for backwards compatibility.")
public class UserServiceLegacy {
    private static Logger LOG = LoggerFactory.getLogger(UserServiceLegacy.class);

    @Context
    private HttpServletRequest request;

    @Context
    private HttpServletResponse response;

    private RESTServicePlugin plugin;

    private UserServiceLegacyController userServiceController;

    @PostConstruct
    public void init() {
        plugin = (RESTServicePlugin) XMPPServer.getInstance().getPluginManager()
                .getPluginByName("REST API").orElse(null);
        userServiceController = UserServiceLegacyController.getInstance();
    }

    @POST
    @Path("/userservice")
    public void userSerivcePostRequest() throws IOException {
        userSerivceRequest();
    }

    @GET
    @Path("/userservice")
    public Response userSerivceRequest() throws IOException {
        // Printwriter for writing out responses to browser
        PrintWriter out = response.getWriter();

        if (!plugin.getAllowedIPs().isEmpty()) {
            // Get client's IP address
            String ipAddress = request.getHeader("x-forwarded-for");
            if (ipAddress == null) {
                ipAddress = request.getHeader("X_FORWARDED_FOR");
                if (ipAddress == null) {
                    ipAddress = request.getHeader("X-Forward-For");
                    if (ipAddress == null) {
                        ipAddress = request.getRemoteAddr();
                    }
                }
            }
            if (!plugin.getAllowedIPs().contains(ipAddress)) {
                LOG.warn("User service rejected service to IP address: " + ipAddress);
                replyError("RequestNotAuthorised", response, out);
                return Response.status(200).build();
            }
        }

        String username = request.getParameter("username");
        String password = request.getParameter("password");
        String name = request.getParameter("name");
        String email = request.getParameter("email");
        String type = request.getParameter("type");
        String secret = request.getParameter("secret");
        String groupNames = request.getParameter("groups");
        String item_jid = request.getParameter("item_jid");
        String sub = request.getParameter("subscription");
        // No defaults, add, delete, update only
        // type = type == null ? "image" : type;

        // Check that our plugin is enabled.
        if (!plugin.isEnabled()) {
            LOG.warn("User service plugin is disabled: " + request.getQueryString());
            replyError("UserServiceDisabled", response, out);
            return Response.status(200).build();
        }

        // Check this request is authorised
        if (secret == null || !secret.equals(plugin.getSecret())) {
            LOG.warn("An unauthorised user service request was received: " + request.getQueryString());
            replyError("RequestNotAuthorised", response, out);
            return Response.status(200).build();
        }

        // Some checking is required on the username
        if (username == null && !"grouplist".equals(type)) {
            replyError("IllegalArgumentException", response, out);
            return Response.status(200).build();
        }

        if ((type.equals("add_roster") || type.equals("update_roster") || type.equals("delete_roster"))
                && (item_jid == null || !(sub == null || sub.equals("-1") || sub.equals("0") || sub.equals("1")
                        || sub.equals("2") || sub.equals("3")))) {
            replyError("IllegalArgumentException", response, out);
            return Response.status(200).build();
        }

        // Check the request type and process accordingly
        try {
            if ("grouplist".equals(type)) {
                String message = "";
                for (String groupname : userServiceController.getAllGroups()) {
                    message += "<groupname>" + groupname + "</groupname>";
                }
                replyMessage(message, response, out);
            } else {
                username = username.trim().toLowerCase();
                username = JID.escapeNode(username);
                username = Stringprep.nodeprep(username);
                if ("add".equals(type)) {
                    userServiceController.createUser(username, password, name, email, groupNames);
                    replyMessage("ok", response, out);
                } else if ("delete".equals(type)) {
                    userServiceController.deleteUser(username);
                    replyMessage("ok", response, out);
                } else if ("enable".equals(type)) {
                    userServiceController.enableUser(username);
                    replyMessage("ok", response, out);
                } else if ("disable".equals(type)) {
                    userServiceController.disableUser(username);
                    replyMessage("ok", response, out);
                } else if ("update".equals(type)) {
                    userServiceController.updateUser(username, password, name, email, groupNames);
                    replyMessage("ok", response, out);
                } else if ("add_roster".equals(type)) {
                    userServiceController.addRosterItem(username, item_jid, name, sub, groupNames);
                    replyMessage("ok", response, out);
                } else if ("update_roster".equals(type)) {
                    userServiceController.updateRosterItem(username, item_jid, name, sub, groupNames);
                    replyMessage("ok", response, out);
                } else if ("delete_roster".equals(type)) {
                    userServiceController.deleteRosterItem(username, item_jid);
                    replyMessage("ok", response, out);
                } else if ("usergrouplist".equals(type)) {
                    String message = "";
                    for (String groupname : userServiceController.getUserGroups(username)) {
                        message += "<groupname>" + groupname + "</groupname>";
                    }
                    replyMessage(message, response, out);
                } else {
                    LOG.warn("The userService servlet received an invalid request of type: " + type);
                    // TODO Do something
                }
            }
        } catch (UserAlreadyExistsException e) {
            replyError("UserAlreadyExistsException", response, out);
        } catch (UserNotFoundException e) {
            replyError("UserNotFoundException", response, out);
        } catch (IllegalArgumentException e) {
            replyError("IllegalArgumentException", response, out);
        } catch (SharedGroupException e) {
            replyError("SharedGroupException", response, out);
        } catch (Exception e) {
            LOG.error("Unexpected error while processing 'userservice' request of type '{}' for username '{}'", type, username, e);
            replyError(e.toString(), response, out);
        }
        return Response.status(200).build();
    }

    private void replyMessage(String message, HttpServletResponse response, PrintWriter out) {
        response.setContentType("text/xml");
        out.println("<result>" + message + "</result>");
        out.flush();
    }

    private void replyError(String error, HttpServletResponse response, PrintWriter out) {
        response.setContentType("text/xml");
        out.println("<error>" + error + "</error>");
        out.flush();
    }
}
