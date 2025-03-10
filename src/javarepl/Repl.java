package javarepl;

import com.googlecode.totallylazy.Function1;
import com.googlecode.totallylazy.Mapper;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.predicates.LogicalPredicate;
import javarepl.console.*;
import javarepl.console.rest.RestConsole;

import java.io.*;
import java.lang.management.ManagementPermission;
import java.lang.reflect.ReflectPermission;
import java.net.SocketPermission;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.util.Collections;
import java.util.List;
import java.util.PropertyPermission;

import static com.googlecode.totallylazy.Callables.compose;
import static com.googlecode.totallylazy.Files.fileOption;
import static com.googlecode.totallylazy.Files.temporaryDirectory;
import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Predicates.not;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.*;
import static com.googlecode.totallylazy.numbers.Numbers.intValue;
import static com.googlecode.totallylazy.numbers.Numbers.valueOf;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static javarepl.Utils.applicationVersion;
import static javarepl.Utils.randomServerPort;
import static javarepl.console.ConsoleConfig.consoleConfig;
import static javarepl.console.ConsoleLog.Type.ERROR;
import static javarepl.console.ConsoleLog.Type.SUCCESS;

public class Repl {
    public static void main(String... args) throws Exception {
        ConsoleLogger logger = systemStreamsLogger();
        boolean sandboxed = isSandboxed(args);
        Integer port = port(args);

        if (!ignoreConsole(args)) {
            logger.info(format("Welcome to JavaREPL version %s (%s, %s, Java %s)",
                    applicationVersion(),
                    sandboxed ? "sandboxed" : "unrestricted",
                    getProperty("java.vm.name"),
                    getProperty("java.version")));
        }

        String[] expressions = sequence(initialExpressions(args))
                .union(sequence(initialExpressionsFromFile()))
                .toArray(new String[0]);

        ConsoleConfig consoleConfig = consoleConfig()
                .historyFile(historyFile(!sandboxed))
                .expressions(expressions)
                .sandboxed(sandboxed)
                .logger(logger);

        RestConsole console = new RestConsole(new TimingOutConsole(new SimpleConsole(consoleConfig), expressionTimeout(args), inactivityTimeout(args)), port);
        ExpressionReader reader = new ExpressionReader(ignoreConsole(args) ? ignoreConsoleInput() : readFromConsole());

        if (sandboxed)
            sandboxApplication(logger);

        console.start();

        do {
            console.execute(reader.readExpression().getOrNull());
            logger.info("");
        } while (true);
    }

    private static List<String> initialExpressionsFromFile() {
        return fileOption(new File("."), "javarepl.init")
            .map(readFile())
            .getOrElse(Collections.EMPTY_LIST);
    }

    private static Function1<File, List<String>> readFile() {
        return new Function1<File, List<String>>() {
            @Override
            public List<String> call(File f) throws Exception {
                List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                System.out.println(l);
                return l;
            }
        };
    }

    private static boolean ignoreConsole(String[] args) {
        return sequence(args).contains("--ignoreConsole");
    }

    private static Mapper<Sequence<String>, String> ignoreConsoleInput() {
        return new Mapper<Sequence<String>, String>() {
            public String call(Sequence<String> strings) throws Exception {
                while (true) {
                    Thread.sleep(100);
                }
            }
        };
    }

    private static Mapper<Sequence<String>, String> readFromConsole() {
        return new Mapper<Sequence<String>, String>() {
            private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

            public String call(Sequence<String> lines) throws Exception {
                return reader.readLine();
            }
        };
    }

    private static String[] initialExpressions(String[] args) {
        return sequence(args)
                .find(startsWith("--expression="))
                .map(replaceAll("--expression=", ""))
                .toSequence()
                .toArray(String.class);
    }

    private static ConsoleLogger systemStreamsLogger() {
        ConsoleLogger logger = new ConsoleLogger(System.out, System.err, false);

        LogicalPredicate<String> ignoredLogs = startsWith("POST /")
                .or(startsWith("GET /"))
                .or(startsWith("Listening on http://"));

        System.setOut(new ConsoleLoggerPrintStream(SUCCESS, ignoredLogs, logger));
        System.setErr(new ConsoleLoggerPrintStream(ERROR, ignoredLogs, logger));

        return logger;
    }

    private static Option<File> historyFile(boolean createFile) {
        return createFile
                ? some(new File(getProperty("user.home"), ".javarepl.history"))
                : none(File.class);
    }

    private static boolean isSandboxed(String[] args) {
        return sequence(args).contains("--sandboxed");
    }

    private static Integer port(String[] args) {
        return sequence(args).find(startsWith("--port=")).map(compose(replaceAll("--port=", ""), compose(valueOf, intValue))).getOrElse(randomServerPort());
    }

    private static Option<Integer> expressionTimeout(String[] args) {
        return sequence(args).find(startsWith("--expressionTimeout=")).map(compose(replaceAll("--expressionTimeout=", ""), compose(valueOf, intValue)));
    }

    private static Option<Integer> inactivityTimeout(String[] args) {
        return sequence(args).find(startsWith("--inactivityTimeout=")).map(compose(replaceAll("--inactivityTimeout=", ""), compose(valueOf, intValue)));
    }

    private static void sandboxApplication(ConsoleLogger logger) throws UnsupportedEncodingException {
        Policy.setPolicy(new Policy() {
            private final PermissionCollection permissions = new Permissions();

            {
                permissions.add(new SocketPermission("*", "accept, connect, resolve"));
                permissions.add(new RuntimePermission("accessClassInPackage.sun.misc.*"));
                permissions.add(new RuntimePermission("accessClassInPackage.sun.misc"));
                permissions.add(new RuntimePermission("getProtectionDomain"));
                permissions.add(new RuntimePermission("accessDeclaredMembers"));
                permissions.add(new RuntimePermission("createClassLoader"));
                permissions.add(new RuntimePermission("closeClassLoader"));
                permissions.add(new RuntimePermission("modifyThreadGroup"));
                permissions.add(new RuntimePermission("getStackTrace"));
                permissions.add(new ManagementPermission("monitor"));
                permissions.add(new ReflectPermission("suppressAccessChecks"));
                permissions.add(new PropertyPermission("*", "read"));
                permissions.add(new FilePermission(temporaryDirectory("JavaREPL").getAbsolutePath() + "/-", "read, write, delete"));
                permissions.add(new FilePermission(sequence(System.getProperty("java.home").split(File.separator)).
                    reverse().
                    dropWhile(not(contains("jdk"))).
                    reverse().
                    toString(File.separator) + "/-", "read"));
                permissions.add(new FilePermission("./-", "read"));

                Sequence<String> extensions = sequence(System.getProperty("java.ext.dirs").split(File.pathSeparator));
                for (String extension : extensions) {
                    permissions.add(new FilePermission(extension, "read"));
                    permissions.add(new FilePermission(extension + "/-", "read"));
                }

            }

            @Override
            public PermissionCollection getPermissions(CodeSource codesource) {
                return permissions;
            }
        });

        System.setSecurityManager(new SecurityManager());
    }
}
