/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.driver.v1;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;
import org.neo4j.driver.v1.util.StubServer;
import org.neo4j.driver.v1.util.TestUtil;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.neo4j.driver.internal.logging.DevNullLogging.DEV_NULL_LOGGING;
import static org.neo4j.driver.internal.util.Matchers.*;
import static org.neo4j.driver.v1.Config.TrustStrategy.trustOnFirstUse;
import static org.neo4j.driver.v1.util.StubServer.INSECURE_CONFIG;

class GraphDatabaseTest
{
    @Test
    void boltSchemeShouldInstantiateDirectDriver() throws Exception
    {
        // Given
        StubServer server = StubServer.start( "dummy_connection.script", 9001 );
        URI uri = URI.create( "bolt://localhost:9001" );

        // When
        Driver driver = GraphDatabase.driver( uri, INSECURE_CONFIG );

        // Then
        assertThat( driver, is( directDriver() ) );

        // Finally
        driver.close();
        assertThat( server.exitStatus(), equalTo( 0 ) );
    }

    @Test
    void boltPlusDiscoverySchemeShouldInstantiateClusterDriver() throws Exception
    {
        // Given
        StubServer server = StubServer.start( "discover_servers.script", 9001 );
        URI uri = URI.create( "bolt+routing://127.0.0.1:9001" );

        // When
        Driver driver = GraphDatabase.driver( uri, INSECURE_CONFIG );

        // Then
        assertThat( driver, is( clusterDriver() ) );

        // Finally
        driver.close();
        assertThat( server.exitStatus(), equalTo( 0 ) );
    }

    @Test
    @SuppressWarnings( "deprecation" )
    void boltPlusDiscoverySchemeShouldNotSupportTrustOnFirstUse()
    {
        URI uri = URI.create( "bolt+routing://127.0.0.1:9001" );

        Config config = Config.build()
                .withEncryption()
                .withTrustStrategy( trustOnFirstUse( new File( "./known_hosts" ) ) )
                .toConfig();

        assertThrows( IllegalArgumentException.class, () -> GraphDatabase.driver( uri, config ) );
    }

    @Test
    void throwsWhenBoltSchemeUsedWithRoutingParams()
    {
        assertThrows( IllegalArgumentException.class, () -> GraphDatabase.driver( "bolt://localhost:7687/?policy=my_policy" ) );
    }

    @Test
    void shouldLogWhenUnableToCreateRoutingDriver() throws Exception
    {
        StubServer server1 = StubServer.start( "non_discovery_server.script", 9001 );
        StubServer server2 = StubServer.start( "non_discovery_server.script", 9002 );

        Logging logging = mock( Logging.class );
        Logger logger = mock( Logger.class );
        when( logging.getLog( anyString() ) ).thenReturn( logger );

        Config config = Config.build()
                .withoutEncryption()
                .withLogging( logging )
                .toConfig();

        List<URI> routingUris = asList(
                URI.create( "bolt+routing://localhost:9001" ),
                URI.create( "bolt+routing://localhost:9002" ) );

        assertThrows( ServiceUnavailableException.class, () -> GraphDatabase.routingDriver( routingUris, AuthTokens.none(), config ) );

        verify( logger ).warn( eq( "Unable to create routing driver for URI: bolt+routing://localhost:9001" ),
                any( Throwable.class ) );

        verify( logger ).warn( eq( "Unable to create routing driver for URI: bolt+routing://localhost:9002" ),
                any( Throwable.class ) );

        assertEquals( 0, server1.exitStatus() );
        assertEquals( 0, server2.exitStatus() );
    }

    @Test
    void shouldRespondToInterruptsWhenConnectingToUnresponsiveServer() throws Exception
    {
        try ( ServerSocket serverSocket = new ServerSocket( 0 ) )
        {
            // setup other thread to interrupt current thread when it blocks
            TestUtil.interruptWhenInWaitingState( Thread.currentThread() );

            try
            {
                assertThrows( ServiceUnavailableException.class, () -> GraphDatabase.driver( "bolt://localhost:" + serverSocket.getLocalPort() ) );
            }
            finally
            {
                // clear interrupted flag
                Thread.interrupted();
            }
        }
    }

    @Test
    void shouldFailToCreateUnencryptedDriverWhenServerDoesNotRespond() throws IOException
    {
        testFailureWhenServerDoesNotRespond( false );
    }

    @Test
    void shouldFailToCreateEncryptedDriverWhenServerDoesNotRespond() throws IOException
    {
        testFailureWhenServerDoesNotRespond( true );
    }

    @Nested
    class FromUriTests {

        @Nested
        class MultipleUris {

            @Nested
            class WithProtocol {

                @Test
                void shouldCreateADriverCreatorWithUris() {
                    // Given
                    final URI uri1 = URI.create("bolt+routing://127.0.0.1:9001");
                    final URI uri2 = URI.create("bolt+routing://127.0.0.1:9002");
                    final List<URI> expectedUris = Arrays.stream(new URI[]{uri1, uri2}).collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri1, uri2);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }

                @Test
                void shouldCreateADriverCreatorWithStrings() {
                    // Given
                    final String uri1 = "bolt+routing://127.0.0.1:9001";
                    final String uri2 = "bolt+routing://127.0.0.1:9002";
                    final List<URI> expectedUris = Arrays.stream(new String[]{uri1, uri2}).map(URI::create).collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri1, uri2);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }
            }

            @Nested
            class WithoutProtocol {
                @Test
                void shouldCreateADriverCreatorWithStrings() {
                    // Given
                    final String uri1 = "127.0.0.1:9001";
                    final String uri2 = "127.0.0.1:9002";
                    final List<URI> expectedUris = Arrays.stream(new String[]{uri1, uri2})
                            .map(s -> "bolt+routing://" + s)
                            .map(URI::create)
                            .collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri1, uri2);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }
            }
        }

        @Nested
        class SingleUri {
            @Nested
            class WithProtocol {
                @Test
                void shouldCreateADriverCreatorWithUri() {
                    // Given
                    final URI uri = URI.create("bolt+routing://127.0.0.1:9001");
                    final List<URI> expectedUris = Arrays.stream(new URI[]{uri}).collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }

                @Test
                void shouldCreateADriverCreatorWithStrings() {
                    // Given
                    final String uri = "bolt+routing://127.0.0.1:9001";
                    final List<URI> expectedUris = Arrays.stream(new String[]{uri}).map(URI::create).collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }
            }

            @Nested
            class WithoutProtocol {
                @Test
                void shouldCreateADriverCreatorWithStrings() {
                    // Given
                    final String uri = "127.0.0.1:9001";
                    final List<URI> expectedUris = Arrays.stream(new String[]{uri})
                            .map(s -> "bolt://" + s)
                            .map(URI::create)
                            .collect(toList());

                    // When
                    final GraphDatabaseDriverCreator actualValue = GraphDatabase.fromUri(uri);

                    // Then
                    final GraphDatabaseDriverCreator expectedValue = new GraphDatabaseDriverCreator(expectedUris, AuthTokens.none(), Config.build().toConfig());
                    assertThat(actualValue, isEqualTo(expectedValue));
                }
            }
        }


    }

    private static void testFailureWhenServerDoesNotRespond( boolean encrypted ) throws IOException
    {
        try ( ServerSocket server = new ServerSocket( 0 ) ) // server that accepts connections but does not reply
        {
            int connectionTimeoutMillis = 1_000;
            Config config = createConfig( encrypted, connectionTimeoutMillis );

            ServiceUnavailableException e = assertThrows( ServiceUnavailableException.class,
                    () -> GraphDatabase.driver( URI.create( "bolt://localhost:" + server.getLocalPort() ), config ) );
            assertEquals( e.getMessage(), "Unable to establish connection in " + connectionTimeoutMillis + "ms" );
        }
    }

    private static Config createConfig( boolean encrypted, int timeoutMillis )
    {
        Config.ConfigBuilder configBuilder = Config.build()
                .withConnectionTimeout( timeoutMillis, MILLISECONDS )
                .withLogging( DEV_NULL_LOGGING );

        if ( encrypted )
        {
            configBuilder.withEncryption();
        }
        else
        {
            configBuilder.withoutEncryption();
        }

        return configBuilder.toConfig();
    }
}
