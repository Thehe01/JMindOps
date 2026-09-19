package com.kama.jmindops.agent.tools;

import com.kama.jmindops.governance.RequiresToolApproval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemToolsSecurityTest {

    @TempDir
    Path workspace;

    @Test
    void readAndListStayInsideConfiguredWorkspace() throws Exception {
        Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(workspace.resolve("notes/todo.md"), "review Agent trace");
        FileSystemTools tools = new FileSystemTools(workspace.toString());

        assertThat(tools.readFile("notes/todo.md")).contains("review Agent trace");
        assertThat(tools.listFiles("notes")).contains("todo.md");
        assertThat(tools.readFile("../outside.txt")).contains("访问被拒绝");
        assertThat(tools.readFile(".env")).contains("访问被拒绝");
    }

    @Test
    void refusesToDeleteWorkspaceRootEvenWhenCalledDirectly() {
        FileSystemTools tools = new FileSystemTools(workspace.toString());

        assertThat(tools.deleteFile(".")).contains("访问被拒绝");
        assertThat(workspace).exists();
    }

    @Test
    void everyMutatingOperationRequiresApproval() throws Exception {
        assertRequiresApproval("writeFile", String.class, String.class);
        assertRequiresApproval("appendToFile", String.class, String.class);
        assertRequiresApproval("deleteFile", String.class);
        assertRequiresApproval("createDirectory", String.class);
    }

    private static void assertRequiresApproval(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = FileSystemTools.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(RequiresToolApproval.class)).isNotNull();
    }
}
