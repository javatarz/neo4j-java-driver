/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Method;

import org.neo4j.driver.internal.spi.ConnectionProvider;
import org.neo4j.driver.internal.spi.PooledConnection;
import org.neo4j.driver.v1.AccessMode;
import org.neo4j.driver.v1.Logger;
import org.neo4j.driver.v1.Logging;
import org.neo4j.driver.v1.Session;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.driver.v1.AccessMode.READ;

public class LeakLoggingNetworkSessionTest
{
    @Rule
    public final TestName testName = new TestName();

    @Test
    public void logsNothingDuringFinalizationIfClosed() throws Exception
    {
        Logging logging = mock( Logging.class );
        Logger log = mock( Logger.class );
        when( logging.getLog( anyString() ) ).thenReturn( log );
        LeakLoggingNetworkSession session = newSession( logging, false );

        finalize( session );

        verify( log, never() ).error( anyString(), any( Throwable.class ) );
    }

    @Test
    public void logsMessageWithStacktraceDuringFinalizationIfLeaked() throws Exception
    {
        Logging logging = mock( Logging.class );
        Logger log = mock( Logger.class );
        when( logging.getLog( anyString() ) ).thenReturn( log );
        LeakLoggingNetworkSession session = newSession( logging, true );
        // begin transaction to make session obtain a connection
        session.beginTransaction();

        finalize( session );

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass( String.class );
        verify( log ).error( messageCaptor.capture(), any( Throwable.class ) );

        assertEquals( 1, messageCaptor.getAllValues().size() );

        String loggedMessage = messageCaptor.getValue();
        assertThat( loggedMessage, startsWith( "Neo4j Session object leaked" ) );
        assertThat( loggedMessage, containsString( "Session was create at" ) );
        assertThat( loggedMessage, containsString(
                getClass().getSimpleName() + "." + testName.getMethodName() )
        );
    }

    private static void finalize( Session session ) throws Exception
    {
        Method finalizeMethod = session.getClass().getDeclaredMethod( "finalize" );
        finalizeMethod.setAccessible( true );
        finalizeMethod.invoke( session );
    }

    private static LeakLoggingNetworkSession newSession( Logging logging, boolean openConnection )
    {
        return new LeakLoggingNetworkSession( connectionProviderMock( openConnection ), READ, null, logging );
    }

    private static ConnectionProvider connectionProviderMock( final boolean openConnection )
    {
        ConnectionProvider provider = mock( ConnectionProvider.class );
        when( provider.acquireConnection( any( AccessMode.class ) ) ).thenAnswer( new Answer<PooledConnection>()
        {
            @Override
            public PooledConnection answer( InvocationOnMock invocation ) throws Throwable
            {
                return connectionMock( openConnection );
            }
        } );
        return provider;
    }

    private static PooledConnection connectionMock( boolean open )
    {
        PooledConnection connection = mock( PooledConnection.class );
        when( connection.isOpen() ).thenReturn( open );
        return connection;
    }
}
