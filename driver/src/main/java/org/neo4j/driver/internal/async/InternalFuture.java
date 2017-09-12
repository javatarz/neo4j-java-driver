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
package org.neo4j.driver.internal.async;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

import org.neo4j.driver.v1.util.Function;

public interface InternalFuture<T> extends Future<T>
{
    EventLoop eventLoop();

    Task<T> asTask();

    <U> InternalFuture<U> thenApply( Function<T,U> fn );

    <U> InternalFuture<U> thenCombine( Function<T,InternalFuture<U>> fn );

    InternalFuture<T> whenComplete( Runnable action );
}