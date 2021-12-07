package org.holicc.cmd.impl;

import org.holicc.cmd.exception.CommandException;
import org.holicc.db.DataBase;
import org.holicc.db.DataEntry;
import org.holicc.db.DataPolicy;
import org.holicc.db.LocalDataBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.holicc.cmd.impl.TestUtils.equalsEntry;

class StringCommandTest {

    DataBase dataBase = new LocalDataBase();
    StringCommand command = new StringCommand(dataBase);

    @BeforeEach
    void prepareData() {
        dataBase.persistInMemory(new DataEntry("a", "1"));
    }

    @ParameterizedTest
    @MethodSource
    void set(String cmd, String exceptResponse, DataEntry exceptEntry) throws CommandException {
        String[] cmds = cmd.split(" ");
        String[] options = null;
        if (cmds.length > 3) {
            options = Arrays.copyOfRange(cmds, 3, cmds.length);
        }
        String response = command.set(cmds[1], cmds[2], options);
        //
        Assertions.assertEquals(exceptResponse, response);
        //
        DataEntry entry = dataBase.getEntry(cmds[1]).orElse(null);
        Assertions.assertNotNull(entry);
        equalsEntry(exceptEntry, entry);
    }

    static Stream<Arguments> set() {
        return Stream.of(
                Arguments.of("set a 2", "OK", new DataEntry("a", "2")),
                Arguments.of("set a 2 NX", "OK", new DataEntry("a", "1")),
                Arguments.of("set a 2 XX", "1", new DataEntry("a", "2", null, DataPolicy.PUT_IF_EXISTS)),
                Arguments.of("set a 2 EX 100", "OK", new DataEntry("a", "2", LocalDateTime.now().plusSeconds(101))),
                Arguments.of("set a 2 PX 200000", "OK", new DataEntry("a", "2", LocalDateTime.now().plusSeconds(201)))
        );
    }

    @ParameterizedTest
    @MethodSource
    void get(String cmd, DataEntry except) throws CommandException {
        String[] cmds = cmd.split(" ");
        dataBase.persistInMemory(except);
        String response = command.get(cmds[1]);
        String exceptStr = except.getValue();
        Assertions.assertEquals(exceptStr, response);
    }

    static Stream<Arguments> get() {
        return Stream.of(
                Arguments.of("get a", new DataEntry("a", "1"))
        );
    }

    @MethodSource
    @ParameterizedTest
    void incr(String key, long except) throws CommandException {
        long actual = command.incr(key);
        Assertions.assertEquals(except, actual);
    }

    static Stream<Arguments> incr() {
        return Stream.of(
                Arguments.of("a", 2),
                Arguments.of("c", 0)
        );
    }


}