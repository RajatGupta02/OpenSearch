/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.javaagent;

import org.opensearch.javaagent.bootstrap.AgentPolicy;

import java.io.FilePermission;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;

import net.bytebuddy.asm.Advice;

/**
 * FileInterceptor
 */
public class FileInterceptor {
    /**
     * FileInterceptor
     */
    public FileInterceptor() {}

    //Byte-buddy needs it to be public to be able to access this field
    public static final Set<String> MUTATING_OPERATIONS = Set.of(
        "write",
        "createFile",
        "createDirectories",
        "createLink",
        "copy",
        "move",
        "newByteChannel"
    );

    public static final Set<String> DELETE_OPERATIONS = Set.of(
        "delete",
        "deleteIfExists"
    );

    /**
     * Intercepts file operations
     *
     * @param args   arguments
     * @param method method
     * @throws Exception exceptions
     */
    @Advice.OnMethodEnter
    @SuppressWarnings("removal")
    @SuppressForbidden(reason = "Using FilePermissions directly is ok")
    public static void intercept(@Advice.AllArguments Object[] args, @Advice.Origin Method method) throws Exception {
        final Policy policy = AgentPolicy.getPolicy();
        if (policy == null) {
            return; /* noop */
        }

        String filePath = null;
        if (args.length > 0 && args[0] instanceof String pathStr) {
            filePath = Paths.get(pathStr).toAbsolutePath().toString();
        } else if (args.length > 0 && args[0] instanceof Path path) {
            filePath = path.toAbsolutePath().toString();
        }

        if (filePath == null) {
            return; // No valid file path found
        }

        final StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        final List<ProtectionDomain> callers = walker.walk(new StackCallerChainExtractor());

        boolean isMutating = MUTATING_OPERATIONS.contains(method.getName());
        boolean isDelete = DELETE_OPERATIONS.contains(method.getName());

        // Check each permission separately
        System.out.println("Method Name = " + method.getName());
        for (final ProtectionDomain domain : callers) {
            // Handle FileChannel.open() separately to check read/write permissions properly
            if (method.getName().equals("open")) {
                if (!policy.implies(domain, new FilePermission(filePath, "read,write"))) {
                    throw new SecurityException("Denied OPEN access to file: " + filePath + ", domain: " + domain);
                }
            }

            // File mutating operations
            if (isMutating && !policy.implies(domain, new FilePermission(filePath, "write"))) {
                throw new SecurityException("Denied WRITE access to file: " + filePath + ", domain: " + domain);
            }

            // File deletion operations
            if (isDelete && !policy.implies(domain, new FilePermission(filePath, "delete"))) {
                throw new SecurityException("Denied DELETE access to file: " + filePath + ", domain: " + domain);
            }
        }
    }
}
