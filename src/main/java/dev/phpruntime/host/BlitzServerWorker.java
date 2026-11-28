package dev.phpruntime.host;

import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * One Blitz server worker: the PHP context that ran the script on this thread, its registered
 * request closure, and the PHP request/response objects for the request currently being
 * handled. The context is already entered because the loop runs inside {@code $server->start()},
 * so invoking a guest callback needs no context switch or queue.
 */
final class BlitzServerWorker {

    private static final byte[] EMPTY = new byte[0];
    private final BlitzServerCore core;
    private final PhpContext php;
    private final BlitzServerState state;
    private final PhpCallable callback;
    private final PhpClass requestClass;
    private final PhpClass responseClass;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    private final ByteArrayOutputStream err = new ByteArrayOutputStream(128);
    /** The request object the PHP builtins see; one per worker, reset for every request. */
    private final PhpRequest hostRequest = PhpRequest.worker();
    private int index;

    BlitzServerWorker(BlitzServerCore core, PhpContext php, BlitzServerState state,
                      PhpCallable callback, int index) {
        this.core = core;
        this.php = php;
        this.state = state;
        this.callback = callback;
        this.index = index;
        this.requestClass = php.lookupClass("Blitz\\Http\\Request");
        this.responseClass = php.lookupClass("Blitz\\Http\\Response");
        if (requestClass == null || responseClass == null) {
            throw new IllegalStateException("Blitz HTTP classes were not registered");
        }
    }

    PhpContext php() {
        return php;
    }

    BlitzServerState state() {
        return state;
    }

    /** Installs the worker's reusable request on the thread for the loop's lifetime. */
    void installHostRequest() {
        hostRequest.install();
    }

    void uninstallHostRequest() {
        PhpRequest.uninstall();
    }

    int index() {
        return index;
    }

    void setIndex(int index) {
        this.index = index;
    }

    BlitzServerInfo info() {
        return core.info();
    }

    /** Called on this worker's selector thread for one complete request. */
    void handle(BlitzHttpRequest request, BlitzHttpConnection connection) {
        out.reset();
        err.reset();
        PhpRequest host = hostRequest;
        host.http = false;
        host.method = request.method();
        host.uri = request.target();
        host.queryString = request.query();
        host.protocol = request.version();
        host.remoteAddr = request.remoteAddr();
        host.serverHost = request.localAddr();
        host.serverPort = request.localPort();
        host.scriptFilename = info().scriptFilename();
        host.body = request.bodyText();
        host.scheduler = GuestScheduler.NONE;
        host.status = 200;
        host.jsonLastError = 0;
        host.responseHeaders.clear();
        host.closeResources();

        BlitzHttpResponseWriter response = new BlitzHttpResponseWriter(connection, request);
        BlitzRequestState requestState = new BlitzRequestState(request, connection.id());
        PhpObject requestObject = new PhpObject(requestClass, php.nextObjectId());
        requestObject.setNativeState(requestState);
        BlitzResponseState responseState = new BlitzResponseState(response);
        PhpObject responseObject = new PhpObject(responseClass, php.nextObjectId());
        responseObject.setNativeState(responseState);

        int shutdownBase = php.shutdownFunctionCount();
        php.beginServerRequest(host, out, err);
        try {
            callback.call2(php, null, requestObject, responseObject);
        } catch (PhpException e) {
            log(request, e.formattedUncaught());
            response.fail(500, "Internal Server Error\n");
        } catch (PhpError e) {
            log(request, errorText(php, e));
            response.fail(500, "Internal Server Error\n");
        } catch (ControlFlow.Exit e) {
            // exit() in a handler ends only this response; the worker keeps serving.
            if (!response.ended()) {
                response.end(EMPTY);
            }
        } catch (Throwable t) {
            log(request, "PHP Fatal error:  Internal error: " + t + "\n");
            response.fail(500, "Internal Server Error\n");
        } finally {
            try {
                php.runShutdownFunctionsFrom(shutdownBase);
            } catch (PhpException e) {
                log(request, e.formattedUncaught());
            } catch (PhpError e) {
                log(request, errorText(php, e));
            } catch (Throwable t) {
                log(request, "PHP Fatal error:  " + t + "\n");
            }
            try {
                php.flush();
            } catch (Throwable t) {
                log(request, "PHP Fatal error:  " + t + "\n");
            }
            if (!response.ended()) {
                byte[] body = out.size() == 0 ? EMPTY : out.toByteArray();
                response.end(body);
            }
            if (err.size() > 0) {
                try {
                    System.err.write(err.toByteArray());
                } catch (IOException ignored) {
                    // diagnostics only
                }
            }
            php.endServerRequest();
            host.closeResources();
            requestState.release();
        }
    }

    private void log(BlitzHttpRequest request, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        System.err.print("blitz http: " + request.method() + " " + request.target() + ": " + message);
        if (!message.endsWith("\n")) {
            System.err.println();
        }
    }

    private static String errorText(PhpContext php, PhpError e) {
        if (e.isCatchable()) {
            try {
                return PhpExceptions.formatUncaught(e.toPhpObject(php, null)) + "\n";
            } catch (Throwable ignored) {
                // fall through to the plain message
            }
        }
        return "PHP Fatal error:  " + e.getMessage() + "\n";
    }
}
