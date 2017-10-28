/*
 * IntellectualServer is a web server, written entirely in the Java language.
 * Copyright (C) 2017 IntellectualSites
 *
 * This program is free software; you can redistribute it andor modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.github.intellectualsites.iserver.implementation;

import com.codahale.metrics.Timer;
import com.github.intellectualsites.iserver.api.cache.CacheApplicable;
import com.github.intellectualsites.iserver.api.config.CoreConfig;
import com.github.intellectualsites.iserver.api.config.Message;
import com.github.intellectualsites.iserver.api.core.IntellectualServer;
import com.github.intellectualsites.iserver.api.core.ServerImplementation;
import com.github.intellectualsites.iserver.api.core.WorkerProcedure;
import com.github.intellectualsites.iserver.api.logging.LogModes;
import com.github.intellectualsites.iserver.api.logging.Logger;
import com.github.intellectualsites.iserver.api.request.HttpMethod;
import com.github.intellectualsites.iserver.api.request.PostRequest;
import com.github.intellectualsites.iserver.api.request.Request;
import com.github.intellectualsites.iserver.api.response.Header;
import com.github.intellectualsites.iserver.api.response.ResponseBody;
import com.github.intellectualsites.iserver.api.session.ISession;
import com.github.intellectualsites.iserver.api.util.Assert;
import com.github.intellectualsites.iserver.api.util.AutoCloseable;
import com.github.intellectualsites.iserver.api.util.LambdaUtil;
import com.github.intellectualsites.iserver.api.validation.RequestValidation;
import com.github.intellectualsites.iserver.api.validation.ValidationException;
import com.github.intellectualsites.iserver.api.views.RequestHandler;
import com.github.intellectualsites.iserver.api.views.errors.ViewException;
import com.github.intellectualsites.iserver.implementation.error.IntellectualServerException;
import org.apache.commons.lang3.ArrayUtils;
import sun.misc.BASE64Encoder;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * This is the worker that is responsible for nearly everything.
 * Feel no pressure, buddy.
 */
final class Worker extends AutoCloseable
{

    private static byte[] empty = "NULL".getBytes();
    private static Queue<Worker> availableWorkers;

    private static final String CONTENT_TYPE = "content_type";

    private final MessageDigest messageDigestMd5;
    private final BASE64Encoder encoder;
    private final WorkerProcedure.WorkerProcedureInstance workerProcedureInstance;
    private final ReusableGzipOutputStream reusableGzipOutputStream;
    private final IntellectualServer server;
    private Request request;
    private BufferedOutputStream output;

    private Worker()
    {
        if ( CoreConfig.contentMd5 )
        {
            MessageDigest temporary = null;
            try
            {
                temporary = MessageDigest.getInstance( "MD5" );
            } catch ( final NoSuchAlgorithmException e )
            {
                Message.MD5_DIGEST_NOT_FOUND.log( e.getMessage() );
            }
            messageDigestMd5 = temporary;
            encoder = new BASE64Encoder();
        } else
        {
            messageDigestMd5 = null;
            encoder = null;
        }

        if ( CoreConfig.gzip )
        {
            this.reusableGzipOutputStream = new ReusableGzipOutputStream();
        } else
        {
            this.reusableGzipOutputStream = null;
        }

        this.workerProcedureInstance = ServerImplementation.getImplementation()
                .getProcedure().getInstance();
        this.server = ServerImplementation.getImplementation();
    }

    /**
     * Setup the handler with a specified number of worker instances
     * @param n Number of worker instances (must be positive)
     */
    static void setup(final int n)
    {
        availableWorkers = new ArrayDeque<>( Assert.isPositive( n ).intValue() );
        LambdaUtil.collectionAssign( () -> availableWorkers, Worker::new, n );
        ServerImplementation.getImplementation().log( "Available workers: " + availableWorkers.size() );
    }

    /**
     * Poll the worker queue until a worker is available.
     * Warning: The thread will be locked until a new worker is available
     * @return The next available worker
     */
    static Worker getAvailableWorker()
    {
        Worker worker = Assert.notNull( availableWorkers ).poll();
        while ( worker == null )
        {
            worker = availableWorkers.poll();
        }
        return worker;
    }

    @Override
    protected void handleClose()
    {
        if ( CoreConfig.gzip )
        {
            try
            {
                this.reusableGzipOutputStream.close();
            } catch ( final Exception e )
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * Compress bytes using gzip
     *
     * @param data Bytes to compress
     * @return GZIP compressed data
     * @throws IOException If compression fails
     */
    private byte[] compress(final byte[] data) throws IOException
    {
        Assert.notNull( data );

        reusableGzipOutputStream.reset();
        reusableGzipOutputStream.write( data );
        reusableGzipOutputStream.finish();
        reusableGzipOutputStream.flush();

        final byte[] compressed = reusableGzipOutputStream.getData();

        Assert.equals( compressed != null && compressed.length > 0, true, "Failed to compress data" );

        return compressed;
    }

    private void handle()
    {
        Optional<ISession> session = server.getSessionManager().getSession( request, output );

        if ( session.isPresent() )
        {
            request.setSession( session.get() );
            server.getSessionManager().setSessionLastActive( session.get().get( "id" ).toString() );
        } else
        {
            Logger.warn( "Could not initialize session!" );
        }

        final RequestHandler requestHandler = server.getRouter().match( request );

        String textContent = "";
        byte[] bytes = empty;

        boolean shouldCache = false;
        boolean cache = false;
        ResponseBody body;

        try
        {
            if ( !requestHandler.getValidationManager().isEmpty() )
            {
                if ( request.getQuery().getMethod() == HttpMethod.POST )
                {
                    for ( final RequestValidation<PostRequest> validator : requestHandler.getValidationManager()
                            .getValidators(
                                    RequestValidation.ValidationStage.POST_PARAMETERS ) )
                    {
                        final RequestValidation.ValidationResult result = validator.validate( request
                                .getPostRequest() );
                        if ( !result.isSuccess() )
                        {
                            throw new ValidationException( result );
                        }
                    }
                } else
                {
                    for ( final RequestValidation<Request.Query> validator : requestHandler.getValidationManager()
                            .getValidators( RequestValidation.ValidationStage.GET_PARAMETERS ) )
                    {
                        final RequestValidation.ValidationResult result = validator.validate( request.getQuery() );
                        if ( !result.isSuccess() )
                        {
                            throw new ValidationException( result );
                        }
                    }
                }
            }

            if ( CoreConfig.Cache.enabled && requestHandler instanceof CacheApplicable
                    && ( (CacheApplicable) requestHandler ).isApplicable( request ) )
            {
                cache = true;
                if ( !server.getCacheManager().hasCache( requestHandler ) )
                {
                    shouldCache = true;
                }
            }

            if ( !cache || shouldCache )
            { // Either it's a non-cached view, or there is no cache stored
                body = requestHandler.handle( request );
            } else
            { // Just read from memory
                body = server.getCacheManager().getCache( requestHandler );
            }

            boolean skip = false;
            if ( body == null )
            {
                final Object redirect = request.getMeta( "internalRedirect" );
                if ( redirect != null && redirect instanceof Request )
                {
                    this.request = (Request) redirect;
                    this.request.removeMeta( "internalRedirect" );
                    handle();
                    return;
                } else
                {
                    skip = true;
                }
            }

            if ( skip )
            {
                return;
            }

            if ( shouldCache )
            {
                server.getCacheManager().setCache( requestHandler, body );
            }

            if ( body.isText() )
            {
                textContent = body.getContent();
            } else
            {
                bytes = body.getBytes();
            }

            for ( final Map.Entry<String, String> postponedCookie : request.postponedCookies.entries() )
            {
                body.getHeader().setCookie( postponedCookie.getKey(), postponedCookie.getValue() );
            }

            // Start: CTYPE
            // Desc: To allow worker procedures to filter based on content type
            final Optional<String> contentType = body.getHeader().get( Header.HEADER_CONTENT_TYPE );
            if ( contentType.isPresent() )
            {
                request.addMeta( CONTENT_TYPE, contentType.get() );
            } else
            {
                request.addMeta( CONTENT_TYPE, null );
            }
            // End: CTYPE

            if ( request.getQuery().getMethod().hasBody() )
            {
                if ( body.isText() )
                {
                    for ( final WorkerProcedure.Handler<String> handler : workerProcedureInstance.getStringHandlers() )
                    {
                        textContent = handler.act( requestHandler, request, textContent );
                    }
                    bytes = textContent.getBytes();
                }

                if ( !workerProcedureInstance.getByteHandlers().isEmpty() )
                {
                    Byte[] wrapper = ArrayUtils.toObject( bytes );
                    for ( final WorkerProcedure.Handler<Byte[]> handler : workerProcedureInstance.getByteHandlers() )
                    {
                        wrapper = handler.act( requestHandler, request, wrapper );
                    }
                    bytes = ArrayUtils.toPrimitive( wrapper );
                }
            }
        } catch ( final Exception e )
        {
            server.log( "Error When Handling Request: %s", e.getMessage(), LogModes.MODE_ERROR );
            e.printStackTrace();

            body = new ViewException( e ).generate( request );
            bytes = body.getContent().getBytes();

            if ( CoreConfig.verbose )
            {
                e.printStackTrace();
            }
        }

        boolean gzip = false;
        if ( CoreConfig.gzip )
        {
            if ( request.getHeader( "Accept-Encoding" ).contains( "gzip" ) )
            {
                gzip = true;
                body.getHeader().set( Header.HEADER_CONTENT_ENCODING, "gzip" );
            } else
            {
                Message.CLIENT_NOT_ACCEPTING_GZIP.log( request.getHeaders() );
            }
        }

        if ( CoreConfig.contentMd5 )
        {
            body.getHeader().set( Header.HEADER_CONTENT_MD5, md5Checksum( bytes ) );
        }

        body.getHeader().apply( output );

        try
        {
            if ( gzip )
            {
                try
                {
                    bytes = compress( bytes );
                } catch ( final IOException e )
                {
                    new IntellectualServerException( "( GZIP ) Failed to compress the bytes" ).printStackTrace();
                }
            }
            output.write( bytes );
        } catch ( final Exception e )
        {
            new IntellectualServerException( "Failed to write to the client", e )
                    .printStackTrace();
        }
        try
        {
            output.flush();
        } catch ( final Exception e )
        {
            new IntellectualServerException( "Failed to flush to the client", e )
                    .printStackTrace();
        }

        if ( CoreConfig.debug )
        {
            server.log( "Request was served by '%s', with the type '%s'. The total length of the content was '%skB'",
                    requestHandler.getName(), body.isText() ? "text" : "bytes", (bytes.length / 1000)
            );
        }

        request.setValid( false );
    }

    /**
     * Prepares a request, then calls {@link #handle}
     * @param remote Client com.plotsquared.iserver.internal.IntellectualSocket
     */
    private void handle(final Socket remote) throws Exception
    {
        // Used for metrics
        final Timer.Context timerContext = ServerImplementation.getImplementation()
                .getMetrics().registerRequestHandling();
        if ( CoreConfig.verbose )
        { // Do we want to output a load of useless information?
            server.log( Message.CONNECTION_ACCEPTED, remote.getInetAddress() );
        }
        final BufferedReader input;
        { // Read the actual request
            try
            {
                input = new BufferedReader( new InputStreamReader( remote.getInputStream() ), CoreConfig.Buffer.in );
                output = new BufferedOutputStream( remote.getOutputStream(), CoreConfig.Buffer.out );

                final List<String> lines = new ArrayList<>();
                String str;
                while ( ( str = input.readLine() ) != null && !str.isEmpty() )
                {
                    lines.add( str );
                }

                request = new Request( lines, remote );

                if ( request.getQuery().getMethod() == HttpMethod.POST )
                {
                    final int cl = Integer.parseInt( request.getHeader( "Content-Length" ).substring( 1 ) );
                    request.setPostRequest( PostRequest.construct( request, cl, input ) );
                }
            } catch ( final Exception e )
            {
                e.printStackTrace();
                return;
            }
        }
        if ( !server.isSilent() )
        {
            server.log( request.buildLog() );
        }
        handle();
        timerContext.stop();
    }

    /**
     * Accepts a remote socket and handles the incoming request,
     * also makes sure its handled and closed down successfully
     * @param remote socket to accept
     */
    void run(final Socket remote)
    {
        if ( remote != null && !remote.isClosed() )
        {
            try
            {
                handle( remote );
            } catch ( final Exception e )
            {
                new IntellectualServerException( "Failed to handle incoming socket", e ).printStackTrace();
            }
        }
        if ( remote != null && !remote.isClosed() )
        {
            try
            {
                remote.close();
            } catch ( final Exception e )
            {
                e.printStackTrace();
            }
        }

        // Add the worker back to the poll
        availableWorkers.add( this );
    }

    /**
     * MD5-ify the input
     * @param input Input text to be digested
     * @return md5-ified digested text
     */
    private String md5Checksum(final byte[] input)
    {
        Assert.notNull( input );

        // Make sure that the buffer is clean
        messageDigestMd5.reset();
        // Update the digest with the current input
        messageDigestMd5.update( input );
        // Now encode it, yay
        return encoder.encode( messageDigestMd5.digest() );
    }

}