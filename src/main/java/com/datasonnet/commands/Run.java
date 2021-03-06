package com.datasonnet.commands;

import com.datasonnet.Document;
import com.datasonnet.Mapper;
import com.datasonnet.StringDocument;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(
        name = "run",
        description = "Transform data using DataSonnet"
)
public class Run implements Callable<Void> {

    @CommandLine.Parameters(
            index = "0",
            description = "Map file"
    )
    private File datasonnet;

    @CommandLine.Parameters(
            index = "1",
            arity = "0..1",
            description = "Input data file (if omitted reads from standard input)"
    )
    private File input;

    @CommandLine.Option(names = {"-a", "--argument"}, split = ",", description = "argument name and value (as JSON)")
    Map<String, String> arguments = new HashMap<>();

    @CommandLine.Option(names = {"-f", "--argument-file"}, split = ",", description = "argument name and file containing the value (as JSON)")
    Map<String, File> argumentFiles = new HashMap<>();

    @CommandLine.Option(names = {"-i", "--import-file"}, description = "file to make available for imports")
    List<File> importFiles = new ArrayList<>();

    @CommandLine.Option(names = {"-n", "--no-wrap"})
    boolean alreadyWrapped;

    @Override
    public Void call() throws Exception {
        Mapper mapper = new Mapper(Main.readFile(datasonnet), combinedArguments().keySet(), imports(), !alreadyWrapped);
        Document result = mapper.transform(new StringDocument(payload(), "application/json"), combinedArguments(), "application/json");
        System.out.println(result.contents());
        return null;
    }

    private String payload() throws IOException {
        if(input == null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            StringBuilder contents = new StringBuilder();
            int current;
            while((current = reader.read()) != -1) {
                contents.appendCodePoint(current);
            }
            return contents.toString();
        } else {
            return Main.readFile(input);
        }
    }

    private Map<String, Document> combinedArguments() throws IOException {
        Map combined = new HashMap<>(arguments);
        for(Map.Entry<String, File> entry : argumentFiles.entrySet()) {
            String contents = Main.readFile(entry.getValue());
            combined.put(entry.getKey(), new StringDocument(contents, "application/json"));
        }
        return combined;
    }

    private Map<String, String> imports() throws IOException {
        Map imports = new HashMap<>();
        for(File importFile : importFiles) {
            String name = importFile.getPath();
            String contents = Main.readFile(importFile);
            imports.put(name, contents);
        }
        return imports;
    }

}
