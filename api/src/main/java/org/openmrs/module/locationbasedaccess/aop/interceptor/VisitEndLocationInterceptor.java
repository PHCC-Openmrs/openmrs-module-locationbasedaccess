/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * <p>
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.locationbasedaccess.aop.interceptor;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.EmptyInterceptor;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.jdbc.Work;
import org.hibernate.type.Type;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.Visit;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.locationbasedaccess.LocationBasedAccessConstants;

/**
 * Only lets a visit be ended from a session logged in at the visit's own location.
 * <p>
 * This is a Hibernate interceptor rather than advice on VisitService, because a visit's stop time can
 * reach the database without VisitService.saveVisit being the call that flushes it: REST sets the
 * property on the loaded visit, and the commit of any other service call in the same request flushes
 * every dirty entity in the shared session. Checking at flush time, with Hibernate's own previous
 * state, catches the open -> ended transition however it gets written.
 * <p>
 * Exempt: daemon threads (e.g. the auto-close visits task), super users and holders of
 * {@link LocationBasedAccessConstants#END_VISITS_AT_ANY_LOCATION_PRIVILEGE}, visits without a location,
 * and everything when {@link LocationBasedAccessConstants#VISIT_END_RESTRICTION_GLOBAL_PROPERTY_NAME}
 * is "false".
 * <p>
 * Nothing here may use the Hibernate session through OpenMRS services: this runs in the middle of a
 * flush. The privilege check is in-memory (role privileges are eager) and the global property is read
 * over plain JDBC on the session's connection, which does not trigger a flush.
 */
public class VisitEndLocationInterceptor extends EmptyInterceptor {

    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory.getLog(VisitEndLocationInterceptor.class);

    @Override
    public boolean onFlushDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
                                String[] propertyNames, Type[] types) {
        // previousState is null only for a detached visit being re-attached; its old stop time is unknown.
        if (!(entity instanceof Visit) || previousState == null) {
            return false;
        }
        int stopIndex = indexOf(propertyNames, "stopDatetime");
        if (stopIndex < 0 || previousState[stopIndex] != null || currentState[stopIndex] == null) {
            return false;
        }
        // The location the visit had before this change: otherwise one update could move the visit to the
        // session's location and end it at the same time.
        int locationIndex = indexOf(propertyNames, "location");
        Location visitLocation = locationIndex < 0 ? null : (Location) previousState[locationIndex];
        checkMayEndVisitAt(visitLocation);
        return false;
    }

    void checkMayEndVisitAt(Location visitLocation) {
        if (visitLocation == null || visitLocation.getLocationId() == null || Daemon.isDaemonThread()) {
            return;
        }
        User user;
        Integer sessionLocationId;
        try {
            user = Context.getAuthenticatedUser();
            sessionLocationId = Context.getUserContext().getLocationId();
        } catch (APIException noUserContext) {
            // No user context: system code outside a request, not someone ending a visit.
            return;
        }
        if (user == null || Daemon.isDaemonUser(user)
                || user.hasPrivilege(LocationBasedAccessConstants.END_VISITS_AT_ANY_LOCATION_PRIVILEGE)) {
            return;
        }
        if (visitLocation.getLocationId().equals(sessionLocationId) || !isRestrictionEnabled()) {
            return;
        }
        throw new APIAuthenticationException(buildMessage(visitLocation));
    }

    static String buildMessage(Location visitLocation) {
        // Don't initialise a lazy proxy mid-flush just for the message.
        String name = Hibernate.isInitialized(visitLocation) ? visitLocation.getName() : null;
        if (StringUtils.isBlank(name)) {
            return "This visit was started at a different location. Log in at that location to end it.";
        }
        return "This visit was started at " + name + ". Log in at " + name + " to end it.";
    }

    protected boolean isRestrictionEnabled() {
        try {
            List<SessionFactory> factories = Context.getRegisteredComponents(SessionFactory.class);
            final String[] value = new String[1];
            factories.get(0).getCurrentSession().doWork(new Work() {
                @Override
                public void execute(Connection connection) throws SQLException {
                    PreparedStatement statement = connection.prepareStatement(
                            "select property_value from global_property where property = ?");
                    try {
                        statement.setString(1, LocationBasedAccessConstants.VISIT_END_RESTRICTION_GLOBAL_PROPERTY_NAME);
                        ResultSet resultSet = statement.executeQuery();
                        if (resultSet.next()) {
                            value[0] = resultSet.getString(1);
                        }
                        resultSet.close();
                    } finally {
                        statement.close();
                    }
                }
            });
            return !"false".equalsIgnoreCase(StringUtils.trim(value[0]));
        } catch (RuntimeException e) {
            log.warn("Could not read " + LocationBasedAccessConstants.VISIT_END_RESTRICTION_GLOBAL_PROPERTY_NAME
                    + "; enforcing the visit-end location rule", e);
            return true;
        }
    }

    private static int indexOf(String[] propertyNames, String name) {
        for (int i = 0; i < propertyNames.length; i++) {
            if (name.equals(propertyNames[i])) {
                return i;
            }
        }
        return -1;
    }
}
