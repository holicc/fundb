package org.holicc.server;

import org.holicc.cmd.CommandWrapper;
import org.holicc.cmd.FunDBCommand;
import org.holicc.cmd.annotation.Command;
import org.holicc.db.DataBase;
import org.holicc.db.LocalDataBase;
import org.holicc.parser.DefaultProtocolParser;
import org.holicc.parser.ProtocolParseException;
import org.holicc.parser.ProtocolParser;
import org.holicc.parser.RedisValue;
import org.reflections.Reflections;
import org.tinylog.Logger;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class FunDBServer {


    private DataBase db;
    private AofPersistence aofPersistence;
    private RdbPersistence rdbPersistence;
    private Map<String, CommandWrapper> commands;

    private final ServerConfig config;
    private final ProtocolParser parser = new DefaultProtocolParser();
    private final PubSubServer pubSubServer = new PubSubServer();

    private static final String BANNER = """
             ▄▄▄██▀▀▀▓█████ ▓█████▄  ██▓  ██████
               ▒██   ▓█   ▀ ▒██▀ ██▌▓██▒▒██    ▒
               ░██   ▒███   ░██   █▌▒██▒░ ▓██▄
            ▓██▄██▓  ▒▓█  ▄ ░▓█▄   ▌░██░  ▒   ██▒
             ▓███▒   ░▒████▒░▒████▓ ░██░▒██████▒▒
             ▒▓▒▒░   ░░ ▒░ ░ ▒▒▓  ▒ ░▓  ▒ ▒▓▒ ▒ ░
             ▒ ░▒░    ░ ░  ░ ░ ▒  ▒  ▒ ░░ ░▒  ░ ░
             ░ ░ ░      ░    ░ ░  ░  ▒ ░░  ░  ░
             ░   ░      ░  ░   ░     ░        ░
                             ░""".indent(3);


    public FunDBServer(ServerConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        System.out.println(BANNER);
        //  init database
        db = initDatabase();
        // register commands
        commands = registerCmd();
        // init persistence
        initPersistence();
        //  load data
        reloadDatabase();
        // create tcp server
        Selector selector = Selector.open();
        //
        String bind = config.getBind();
        int port = config.getPort();
        InetSocketAddress address = new InetSocketAddress(bind, port);
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.configureBlocking(false);
        channel.register(selector, channel.validOps());
        channel.bind(address);
        //
        Logger.info("server start at: {}:{} ...", bind, port);
        while (selector.isOpen()) {
            selector.select(key -> {
                try {
                    if (key.isAcceptable()) {
                        register(selector, channel);
                    }
                    if (key.isReadable()) {
                        onRead(key);
                    }
                    if (key.isWritable()) {
                        onWrite(key);
                    }
                } catch (Exception e) {
                    key.cancel();
                    Logger.error(e.getMessage());
                }
            }, 1000L);
            // TODO
            Optional.ofNullable(config.getAppendfsync())
                    .ifPresent(af -> {
                        if (af.equals(ServerConfig.EVERY_SECOND_APPEND_FSYNC)) {
                            try {
                                aofPersistence.fsync();
                            } catch (IOException e) {
                                Logger.error("do file sync failed {}", e.getMessage());
                            }
                        }
                    });
        }
    }

    public Response doCommand(RedisValue redisValue, byte[] array) throws ProtocolParseException, IOException {
        List<RedisValue> args = (List<RedisValue>) redisValue.getValue();
        if (args.isEmpty()) throw new ProtocolParseException("none command data");
        String commandName = args.remove(0).getValueAsString().toUpperCase(Locale.ROOT);
        if (!commands.containsKey(commandName) && !args.isEmpty()) {
            String subCommand = args.remove(0).getValueAsString().toUpperCase(Locale.ROOT);
            commandName = commandName + "-" + subCommand;
        }
        CommandWrapper command = commands.get(commandName);
        if (command != null) {
            if (command.persistence()) {
                persistence(array);
            }
            return command.execute(args);
        }
        return Response.Error("unknown command [" + commandName + "]");
    }

    private void initPersistence() {
        if (config.isAppendOnly()) {
            aofPersistence = new AofPersistence(config);
        }
    }

    private DataBase initDatabase() {
        if (config.isClusterEnabled()) {
            // TODO cluster enable
            Logger.debug("init cluster database");
            return null;
        } else {
            Logger.debug("init local database");
            return new LocalDataBase();
        }
    }

    private void register(Selector selector, ServerSocketChannel serverSocketChannel) throws IOException {
        SocketChannel client = serverSocketChannel.accept();
        client.configureBlocking(false);
        client.register(selector, client.validOps());
    }

    private void reloadDatabase() throws IOException, ProtocolParseException {
        if (config.isAppendOnly()) {
            String aofFile = config.getDir() + config.getAppendFileName();
            Path path = Path.of(aofFile);
            if (Files.notExists(path)) {
                Logger.warn("can not find aof file in: {}", aofFile);
                return;
            }
            byte[] bytes = Files.readAllBytes(path);
            int pos = 0;
            while (pos < bytes.length) {
                RedisValue parse = parser.parse(bytes, pos);
                doCommand(parse, bytes);
                pos = parse.getWord().end();
            }
        }
    }

    private void onRead(SelectionKey key) throws IOException, ProtocolParseException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(512);
        int size = channel.read(buffer);
        // peer socket closed
        if (size == -1) {
            throw new IOException("peer socket closed");
        }
        if (size > 0) {
            byte[] array = buffer.array();
            RedisValue parse = parser.parse(array, 0);
            key.attach(doCommand(parse, array));
        }
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void onWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Object attachment = key.attachment();
        if (attachment != null) {
            Response resp = (Response) key.attachment();
            ByteBuffer wrap = ByteBuffer.wrap(resp.toBytes());
            channel.write(wrap);
        }
        key.interestOps(SelectionKey.OP_READ);
    }

    private void persistence(byte[] array) throws IOException {
        if (config.isAppendOnly()) {
            aofPersistence.write(array);
            if (config.getAppendfsync().equals(ServerConfig.ALWAYS_APPEND_FSYNC)) {
                aofPersistence.fsync();
            }
        }
    }

    private Map<String, CommandWrapper> registerCmd() throws InvocationTargetException, InstantiationException, IllegalAccessException {
        Reflections reflections = new Reflections("org.holicc.cmd");
        Map<String, CommandWrapper> cmds = new HashMap<>();
        Set<Class<? extends FunDBCommand>> commands = reflections.getSubTypesOf(FunDBCommand.class);
        for (Class<? extends FunDBCommand> aClass : commands) {
            FunDBCommand instance = newInstance(aClass);
            Set<Method> methods = Arrays.stream(aClass.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Command.class))
                    .collect(Collectors.toSet());
            for (Method method : methods) {
                Command annotation = method.getAnnotation(Command.class);
                String commandName = annotation.subCommand().equals("") ?
                        annotation.name() : annotation.name() + "-" + annotation.subCommand();
                if (!cmds.containsKey(commandName)) {
                    cmds.put(commandName, new CommandWrapper(instance, annotation.persistence(), method));
                } else {
                    Logger.warn("command {} exists !", commandName);
                }
            }
        }
        return cmds;
    }

    private FunDBCommand newInstance(Class<? extends FunDBCommand> aClass) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        Constructor<? extends FunDBCommand> constructor = (Constructor<? extends FunDBCommand>) aClass.getDeclaredConstructors()[0];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length == 0) return constructor.newInstance();
        List<Object> params = new ArrayList<>();
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType.equals(DataBase.class)) {
                params.add(db);
            } else if (parameterType.equals(ServerConfig.class)) {
                params.add(config);
            } else if (parameterType.equals(PubSubServer.class)) {
                params.add(pubSubServer);
            }
        }
        return constructor.newInstance(params.toArray());
    }
}
