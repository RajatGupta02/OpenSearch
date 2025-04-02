package org.opensearch.javaagent;

import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.javaagent.bootstrap.AgentPolicy;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.security.Permission;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.io.FilePermission;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.nio.ByteBuffer;

import java.io.FileWriter;
import java.io.FileReader;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.io.FileInputStream;
import static org.junit.Assert.*;


public class FileInterceptorIntegTests extends OpenSearchTestCase {

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testFileInputStream() throws Exception {
        // Ensure /tmp exists and is writable
        Path tmpDir = Path.of("/tmp");
        assertTrue("Tmp directory should exist", Files.exists(tmpDir));
        assertTrue("Tmp directory should be writable", Files.isWritable(tmpDir));
        
        // Create a unique file name
        String fileName = "test-" + randomAlphaOfLength(8) + ".txt";
        Path tempPath = tmpDir.resolve(fileName);
        
        // Ensure the file doesn't exist
        Files.deleteIfExists(tempPath);
        
        // Write content
        String content = "test content";
        Files.write(tempPath, content.getBytes(StandardCharsets.UTF_8));
        
        // Verify file creation
        assertTrue("File should exist", Files.exists(tempPath));
        assertTrue("File should be readable", Files.isReadable(tempPath));
        assertEquals("File should have correct content", content, 
                    Files.readString(tempPath, StandardCharsets.UTF_8));
        
        File tempFile = tempPath.toFile();

        try {
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                byte[] buffer = new byte[100];
                int bytesRead = fis.read(buffer);
                String readContent = new String(buffer, 0, bytesRead);
                assertEquals("test content", readContent.trim());
            }
        } finally {
            // Clean up
            Files.deleteIfExists(tempPath);
        }
    }

    public void testOpenForReadAndWrite() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path tempPath = tmpDir.resolve("test-open-rw-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Test open for read and write
            try (FileChannel channel = FileChannel.open(tempPath, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                
                // Write content
                String content = "test content";
                ByteBuffer writeBuffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                channel.write(writeBuffer);
                
                // Read content back
                channel.position(0); // Reset position to start
                ByteBuffer readBuffer = ByteBuffer.allocate(100);
                channel.read(readBuffer);
                readBuffer.flip();
                
                String readContent = StandardCharsets.UTF_8.decode(readBuffer).toString();
                assertEquals("Content should match", content, readContent);
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    public void testCopy() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path sourcePath = tmpDir.resolve("test-source-" + randomAlphaOfLength(8) + ".txt");
        Path targetPath = tmpDir.resolve("test-target-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Create source file
            String content = "test content";
            Files.write(sourcePath, content.getBytes(StandardCharsets.UTF_8));
            
            // Test copy operation
            Files.copy(sourcePath, targetPath);
            
            // Verify copy
            assertTrue("Target file should exist", Files.exists(targetPath));
            assertEquals("Content should match", 
                Files.readString(sourcePath), 
                Files.readString(targetPath));
        } finally {
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(targetPath);
        }
    }

    public void testCreateFile() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path tempPath = tmpDir.resolve("test-create-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Test createFile operation
            Files.createFile(tempPath);
            
            // Verify file creation
            assertTrue("File should exist", Files.exists(tempPath));
            assertTrue("Should be a regular file", Files.isRegularFile(tempPath));
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    public void testMove() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path sourcePath = tmpDir.resolve("test-source-" + randomAlphaOfLength(8) + ".txt");
        Path targetPath = tmpDir.resolve("test-target-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Create source file
            String content = "test content";
            Files.write(sourcePath, content.getBytes(StandardCharsets.UTF_8));
            
            // Test move operation
            Files.move(sourcePath, targetPath);
            
            // Verify move
            assertFalse("Source file should not exist", Files.exists(sourcePath));
            assertTrue("Target file should exist", Files.exists(targetPath));
            assertEquals("Content should match", content, Files.readString(targetPath));
        } finally {
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(targetPath);
        }
    }

    public void testCreateLink() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path originalPath = tmpDir.resolve("test-original-" + randomAlphaOfLength(8) + ".txt");
        Path linkPath = tmpDir.resolve("test-link-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Create source file
            Files.write(originalPath, "test content".getBytes(StandardCharsets.UTF_8));
            
            // Test createLink operation
            Files.createLink(linkPath, originalPath);
            
            // Verify link creation
            assertTrue("Link should exist", Files.exists(linkPath));
            assertEquals("File contents should be same", 
                Files.readString(originalPath), 
                Files.readString(linkPath));
        } finally {
            Files.deleteIfExists(linkPath);
            Files.deleteIfExists(originalPath);
        }
    }

    public void testDelete() throws Exception {
        Path tmpDir = Path.of("/tmp");
        Path tempPath = tmpDir.resolve("test-delete-" + randomAlphaOfLength(8) + ".txt");
        
        try {
            // Create a file with some content
            String content = "test content";
            Files.write(tempPath, content.getBytes(StandardCharsets.UTF_8));
            
            // Verify file exists before deletion
            assertTrue("File should exist before deletion", Files.exists(tempPath));
            assertEquals("File should have correct content", content, 
                Files.readString(tempPath, StandardCharsets.UTF_8));
            
            // Test delete operation - FileInterceptor should intercept this
            Files.delete(tempPath);
            
            // Verify deletion
            assertFalse("File should not exist after deletion", Files.exists(tempPath));
            
        } finally {
            // Cleanup in case test fails
            Files.deleteIfExists(tempPath);
        }
    }

}