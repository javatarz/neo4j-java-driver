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
package org.neo4j.driver.internal.messaging.v1;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.neo4j.driver.internal.Bookmark;
import org.neo4j.driver.internal.ExplicitTransaction;
import org.neo4j.driver.internal.InternalStatementResultCursor;
import org.neo4j.driver.internal.handlers.BeginTxResponseHandler;
import org.neo4j.driver.internal.handlers.CommitTxResponseHandler;
import org.neo4j.driver.internal.handlers.InitResponseHandler;
import org.neo4j.driver.internal.handlers.NoOpResponseHandler;
import org.neo4j.driver.internal.handlers.PullAllResponseHandler;
import org.neo4j.driver.internal.handlers.RollbackTxResponseHandler;
import org.neo4j.driver.internal.handlers.RunResponseHandler;
import org.neo4j.driver.internal.handlers.SessionPullAllResponseHandler;
import org.neo4j.driver.internal.handlers.TransactionPullAllResponseHandler;
import org.neo4j.driver.internal.messaging.BoltProtocol;
import org.neo4j.driver.internal.messaging.Message;
import org.neo4j.driver.internal.messaging.MessageFormat;
import org.neo4j.driver.internal.messaging.request.InitMessage;
import org.neo4j.driver.internal.messaging.request.PullAllMessage;
import org.neo4j.driver.internal.messaging.request.RunMessage;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.spi.ResponseHandler;
import org.neo4j.driver.internal.util.Futures;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.Value;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.neo4j.driver.internal.async.ChannelAttributes.messageDispatcher;
import static org.neo4j.driver.v1.Values.ofValue;

public class BoltProtocolV1 implements BoltProtocol
{
    public static final int VERSION = 1;

    public static final BoltProtocol INSTANCE = new BoltProtocolV1();

    private static final String BEGIN_QUERY = "BEGIN";
    private static final Message BEGIN_MESSAGE = new RunMessage( BEGIN_QUERY );
    private static final Message COMMIT_MESSAGE = new RunMessage( "COMMIT" );
    private static final Message ROLLBACK_MESSAGE = new RunMessage( "ROLLBACK" );

    @Override
    public MessageFormat createMessageFormat()
    {
        return new MessageFormatV1();
    }

    @Override
    public void initializeChannel( String userAgent, Map<String,Value> authToken, ChannelPromise channelInitializedPromise )
    {
        Channel channel = channelInitializedPromise.channel();

        InitMessage message = new InitMessage( userAgent, authToken );
        InitResponseHandler handler = new InitResponseHandler( channelInitializedPromise );

        messageDispatcher( channel ).queue( handler );
        channel.writeAndFlush( message, channel.voidPromise() );
    }

    @Override
    public CompletionStage<Void> beginTransaction( Connection connection, Bookmark bookmark )
    {
        if ( bookmark.isEmpty() )
        {
            connection.write(
                    BEGIN_MESSAGE, NoOpResponseHandler.INSTANCE,
                    PullAllMessage.PULL_ALL, NoOpResponseHandler.INSTANCE );

            return Futures.completedWithNull();
        }
        else
        {
            CompletableFuture<Void> beginTxFuture = new CompletableFuture<>();
            connection.writeAndFlush(
                    new RunMessage( BEGIN_QUERY, bookmark.asBeginTransactionParameters() ), NoOpResponseHandler.INSTANCE,
                    PullAllMessage.PULL_ALL, new BeginTxResponseHandler( beginTxFuture ) );

            return beginTxFuture;
        }
    }

    @Override
    public CompletionStage<Void> commitTransaction( Connection connection, ExplicitTransaction tx )
    {
        CompletableFuture<Void> commitFuture = new CompletableFuture<>();

        ResponseHandler pullAllHandler = new CommitTxResponseHandler( commitFuture, tx );
        connection.writeAndFlush(
                COMMIT_MESSAGE, NoOpResponseHandler.INSTANCE,
                PullAllMessage.PULL_ALL, pullAllHandler );

        return commitFuture;
    }

    @Override
    public CompletionStage<Void> rollbackTransaction( Connection connection )
    {
        CompletableFuture<Void> rollbackFuture = new CompletableFuture<>();

        ResponseHandler pullAllHandler = new RollbackTxResponseHandler( rollbackFuture );
        connection.writeAndFlush(
                ROLLBACK_MESSAGE, NoOpResponseHandler.INSTANCE,
                PullAllMessage.PULL_ALL, pullAllHandler );

        return rollbackFuture;
    }

    @Override
    public CompletionStage<InternalStatementResultCursor> runInAutoCommitTransaction( Connection connection, Statement statement, boolean waitForRunResponse )
    {
        return runStatement( connection, statement, null, waitForRunResponse );
    }

    @Override
    public CompletionStage<InternalStatementResultCursor> runInExplicitTransaction( Connection connection, Statement statement, ExplicitTransaction tx,
            boolean waitForRunResponse )
    {
        return runStatement( connection, statement, tx, waitForRunResponse );
    }

    private static CompletionStage<InternalStatementResultCursor> runStatement( Connection connection, Statement statement,
            ExplicitTransaction tx, boolean waitForRunResponse )
    {
        String query = statement.text();
        Map<String,Value> params = statement.parameters().asMap( ofValue() );

        CompletableFuture<Void> runCompletedFuture = new CompletableFuture<>();
        RunResponseHandler runHandler = new RunResponseHandler( runCompletedFuture );
        PullAllResponseHandler pullAllHandler = newPullAllHandler( statement, runHandler, connection, tx );

        connection.writeAndFlush(
                new RunMessage( query, params ), runHandler,
                PullAllMessage.PULL_ALL, pullAllHandler );

        if ( waitForRunResponse )
        {
            // wait for response of RUN before proceeding
            return runCompletedFuture.thenApply( ignore ->
                    new InternalStatementResultCursor( runHandler, pullAllHandler ) );
        }
        else
        {
            return completedFuture( new InternalStatementResultCursor( runHandler, pullAllHandler ) );
        }
    }

    private static PullAllResponseHandler newPullAllHandler( Statement statement, RunResponseHandler runHandler,
            Connection connection, ExplicitTransaction tx )
    {
        if ( tx != null )
        {
            return new TransactionPullAllResponseHandler( statement, runHandler, connection, tx );
        }
        return new SessionPullAllResponseHandler( statement, runHandler, connection );
    }
}
