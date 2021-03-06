/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.util.security.shiro.dao;

import java.io.IOException;
import java.io.Serializable;

import javax.inject.Inject;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDBCSessionDao extends CachingSessionDAO {

    private static final Logger log = LoggerFactory.getLogger(JDBCSessionDao.class);

    private final JDBCSessionSqlDao jdbcSessionSqlDao;

    @Inject
    public JDBCSessionDao(final IDBI dbi) {
        this.jdbcSessionSqlDao = dbi.onDemand(JDBCSessionSqlDao.class);
    }

    @Override
    protected void doUpdate(final Session session) {
        // The look-up should be cheap (most likely cached)
        final Session previousSession = readSession(session.getId());

        if (SessionUtils.sameSession(previousSession, session)) {
            // Only the last access time attribute was updated.
            // Avoid writing the state to disk for each request: we don't care so much about precision in the database,
            // we just want to make sure the session doesn't timeout too early.
            // Note also that in the case of a single node (or distributed cache), the timeout computation
            // will be correct (because the cache value is correct).
            // See https://github.com/killbill/killbill/issues/326
            if (!SessionUtils.accessedRecently(previousSession, session)) {
                final DateTime lastAccessTime = new DateTime(session.getLastAccessTime(), DateTimeZone.UTC);
                final Long sessionId = Long.valueOf(session.getId().toString());
                jdbcSessionSqlDao.updateLastAccessTime(lastAccessTime, sessionId);
            } else if (session instanceof SimpleSession) {
                // Hack to override the value in the cache so subsequent requests see the (stale) value on disk
                ((SimpleSession) session).setLastAccessTime(previousSession.getLastAccessTime());
            }
        } else {
            // Various fields were changed, update the full row
            jdbcSessionSqlDao.update(new SessionModelDao(session));
        }
    }

    @Override
    protected void doDelete(final Session session) {
        jdbcSessionSqlDao.delete(new SessionModelDao(session));
    }

    @Override
    protected Serializable doCreate(final Session session) {
        final Serializable sessionId = jdbcSessionSqlDao.inTransaction(new Transaction<Long, JDBCSessionSqlDao>() {
            @Override
            public Long inTransaction(final JDBCSessionSqlDao transactional, final TransactionStatus status) throws Exception {
                transactional.create(new SessionModelDao(session));
                return transactional.getLastInsertId();
            }
        });
        // See SessionModelDao#toSimpleSession for why we use toString()
        assignSessionId(session, sessionId.toString());
        return sessionId;
    }

    @Override
    public Session readSession(final Serializable sessionId) throws UnknownSessionException {
        final Session session = super.readSession(sessionId);

        // Clone the session to avoid making changes to the existing one in the cache.
        // This is required for the lookup in doUpdate to work
        final SimpleSession clonedSession = new SimpleSession();
        clonedSession.setId(session.getId());
        clonedSession.setStartTimestamp(session.getStartTimestamp());
        clonedSession.setLastAccessTime(session.getLastAccessTime());
        clonedSession.setTimeout(session.getTimeout());
        clonedSession.setHost(session.getHost());
        clonedSession.setAttributes(SessionUtils.getSessionAttributes(session));

        if (session instanceof SimpleSession) {
            clonedSession.setStopTimestamp(((SimpleSession) session).getStopTimestamp());
            clonedSession.setExpired(((SimpleSession) session).isExpired());
        }

        return clonedSession;
    }

    @Override
    protected Session doReadSession(final Serializable sessionId) {
        // Shiro should not pass us a null sessionId, but be safe...
        if (sessionId == null) {
            return null;
        }

        // Ignore unsupported JSESSIONID cookies
        final Long recordId;
        try {
            recordId = Long.parseLong(sessionId.toString().trim());
        } catch (final NumberFormatException e) {
            return null;
        }

        final SessionModelDao sessionModelDao = jdbcSessionSqlDao.read(recordId);
        if (sessionModelDao == null) {
            return null;
        }

        try {
            return sessionModelDao.toSimpleSession();
        } catch (final IOException e) {
            log.warn("Corrupted cookie", e);
            return null;
        }
    }
}
