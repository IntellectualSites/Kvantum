/*
 *    Copyright (C) 2017 IntellectualSites
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.kvantum.server.implementation.debug;

import xyz.kvantum.server.api.request.AbstractRequest;
import xyz.kvantum.server.api.views.requesthandler.Middleware;
import xyz.kvantum.server.api.views.requesthandler.MiddlewareQueue;

public final class DebugRedirectMiddleware extends Middleware
{

    @Override
    public void handle(final AbstractRequest request, final MiddlewareQueue queue)
    {
        if ( !request.getQuery().getParameters().containsKey( "debug" ) )
        {
            request.internalRedirect( "" );
        } else
        {
            queue.handle( request );
        }
    }

}
