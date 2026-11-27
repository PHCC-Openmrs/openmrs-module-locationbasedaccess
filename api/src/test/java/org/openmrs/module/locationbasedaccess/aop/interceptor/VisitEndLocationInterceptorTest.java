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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.Date;

import org.junit.After;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.User;
import org.openmrs.Visit;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.locationbasedaccess.LocationBasedAccessConstants;

/**
 * Plain unit tests (no Spring context): the module's context-sensitive tests don't load on Java 17
 * against core 2.0.5, so these stub the user context with small subclasses instead.
 */
public class VisitEndLocationInterceptorTest {

    private static final String[] PROPERTY_NAMES = { "patient", "location", "startDatetime", "stopDatetime" };

    private static final int SITE_A = 1;

    private static final int SITE_B = 2;

    private boolean restrictionEnabled = true;

    private final VisitEndLocationInterceptor interceptor = new VisitEndLocationInterceptor() {
        @Override
        protected boolean isRestrictionEnabled() {
            return restrictionEnabled;
        }
    };

    @After
    public void clearUserContext() {
        Context.clearUserContext();
    }

    @Test
    public void endingAVisitFromAnotherLocation_isRefused() {
        loginAt(SITE_B, user(false));
        try {
            flushVisitEnd(location(SITE_A, "Deir Al-Balah PHCC"));
            fail("expected the visit end to be refused");
        }
        catch (APIAuthenticationException expected) {
            assertEquals("This visit was started at Deir Al-Balah PHCC. Log in at Deir Al-Balah PHCC to end it.",
                    expected.getMessage());
        }
    }

    @Test(expected = APIAuthenticationException.class)
    public void endingAVisitWithNoSessionLocation_isRefused() {
        loginAt(null, user(false));
        flushVisitEnd(location(SITE_A, "Site A"));
    }

    @Test
    public void endingAVisitFromItsOwnLocation_isAllowed() {
        loginAt(SITE_A, user(false));
        assertFalse(flushVisitEnd(location(SITE_A, "Site A")));
    }

    @Test
    public void holderOfTheAnyLocationPrivilege_mayEndVisitsElsewhere() {
        loginAt(SITE_B, user(true));
        flushVisitEnd(location(SITE_A, "Site A"));
    }

    @Test
    public void globalPropertyFalse_turnsTheRuleOff() {
        restrictionEnabled = false;
        loginAt(SITE_B, user(false));
        flushVisitEnd(location(SITE_A, "Site A"));
    }

    @Test
    public void visitWithoutLocation_isNotRestricted() {
        loginAt(SITE_B, user(false));
        flushVisitEnd(null);
    }

    @Test(expected = APIAuthenticationException.class)
    public void movingTheVisitToTheSessionLocationWhileEndingIt_isRefused() {
        loginAt(SITE_B, user(false));
        Object[] previous = state(location(SITE_A, "Site A"), null);
        Object[] current = state(location(SITE_B, "Site B"), new Date());
        interceptor.onFlushDirty(new Visit(), 1, current, previous, PROPERTY_NAMES, null);
    }

    @Test
    public void editingAnAlreadyEndedVisit_isNotTreatedAsEndingIt() {
        loginAt(SITE_B, user(false));
        Object[] previous = state(location(SITE_A, "Site A"), new Date(1000));
        Object[] current = state(location(SITE_A, "Site A"), new Date(2000));
        interceptor.onFlushDirty(new Visit(), 1, current, previous, PROPERTY_NAMES, null);
    }

    @Test
    public void otherEntities_areIgnored() {
        loginAt(SITE_B, user(false));
        Object[] previous = state(location(SITE_A, "Site A"), null);
        Object[] current = state(location(SITE_A, "Site A"), new Date());
        interceptor.onFlushDirty(new Object(), 1, current, previous, PROPERTY_NAMES, null);
    }

    @Test
    public void unnamedLocation_getsAGenericMessage() {
        assertEquals("This visit was started at a different location. Log in at that location to end it.",
                VisitEndLocationInterceptor.buildMessage(new Location(SITE_A)));
    }

    private boolean flushVisitEnd(Location visitLocation) {
        Object[] previous = state(visitLocation, null);
        Object[] current = state(visitLocation, new Date());
        return interceptor.onFlushDirty(new Visit(), 1, current, previous, PROPERTY_NAMES, null);
    }

    private static Object[] state(Location location, Date stop) {
        return new Object[] { null, location, new Date(0), stop };
    }

    private static Location location(int id, String name) {
        Location location = new Location(id);
        location.setName(name);
        return location;
    }

    private static User user(final boolean mayEndAnywhere) {
        User user = new User() {
            @Override
            public boolean hasPrivilege(String privilege) {
                return mayEndAnywhere
                        && LocationBasedAccessConstants.END_VISITS_AT_ANY_LOCATION_PRIVILEGE.equals(privilege);
            }
        };
        user.setUuid("test-user-uuid");
        return user;
    }

    private static void loginAt(final Integer locationId, final User user) {
        Context.setUserContext(new UserContext() {
            @Override
            public User getAuthenticatedUser() {
                return user;
            }

            @Override
            public Integer getLocationId() {
                return locationId;
            }
        });
    }
}
