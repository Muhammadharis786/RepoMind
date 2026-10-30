package com.haris.reporead.Service;


import com.google.genai.types.Language;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


//import dev.langchain4j.data.document.splitter.DocumentSplitters;
//import dev.langchain4j.data.document.DocumentSplitter;

@Service
public class RepoService {
    private final ChatClient openaichatclient;
    private final ChatClient genaichatclient;

    private final VectorStore vectorStore;
    private final ChatClient.Builder openaitransformermodel;
    private final ChatClient.Builder genaitransformermodel;


    // Sirf in types ki files lenge (images, jar, pdf wagera nahi)
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("java", "md", "css", "txt", "xml", "yml", "yaml", "properties", "json", "sql", "html", "js", "ts", "py");
    // In folders ko ignore karenge
    private static final List<String> IGNORED_FOLDERS =
            List.of("/.git/", "/node_modules/", "/target/", "/build/", "/.idea/");


    // Is se bari file skip (200 KB)
    private static final int MAX_FILE_SIZE = 200_000;

    public RepoService(@Qualifier("openaichatmodel") ChatClient openaichatclient,
                       @Qualifier("genaichatmodel") ChatClient genaichatclient,
                       VectorStore vectorStore,
                       OpenAiChatModel openAiChatModel,
                       GoogleGenAiChatModel genaichatmodel
    ) {
        this.openaichatclient = openaichatclient;
        this.genaichatclient = genaichatclient;
        this.vectorStore = vectorStore;
        this.openaitransformermodel = ChatClient.builder(openAiChatModel); // memory advisor ke bina
        this.genaitransformermodel = ChatClient.builder(genaichatmodel); // memory advisor ke bina

    }


    public ResponseEntity<Flux<String>> streamoutputwithquestionansweradvisor(String userid, String query) {

        QueryTransformer compressquery = CompressionQueryTransformer.builder()
                .chatClientBuilder(openaitransformermodel)
                .build();


        TranslationQueryTransformer translater = TranslationQueryTransformer.builder()
                .chatClientBuilder(openaitransformermodel)
                .targetLanguage("english")

                .build();

        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .topK(10)
                .similarityThreshold(0.5)
                .vectorStore(vectorStore)
                .build();

        DocumentRetriever loggingRetriever = query2 -> {
            List<Document> docs = retriever.retrieve(query2);
            System.out.println("RETRIEVAL QUERY: " + query2.text());
            System.out.println("DOCS FOUND: " + docs.size());
            docs.forEach(d -> System.out.println(
                    " - id=" + d.getId()
                            + " | score=" + d.getScore()
                            + " | category=" + d.getMetadata().get("category")
                            + " | text=" + d.getText()));
            return docs;
        };
        RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(compressquery, translater)
                .documentRetriever(loggingRetriever)
                .build();

        var chatreponse = openaichatclient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userid).advisors(advisor))
                .user(query)
                .stream()
                .content();
        return ResponseEntity.ok().body(chatreponse);
    }

    public ResponseEntity<?> FetchRepotoLoadIntoVectorStore(String githubUrl) {
        try {
            // STEP 1: GitHub Link ko Parse Karo
            // Examples supported:
            // - https://github.com/owner/repo
            // - https://github.com/owner/repo.git
            // - https://github.com/owner/repo/tree/main

            String cleanUrl = githubUrl.trim().replace(".git", "");
            String[] parts = cleanUrl.split("/");

            if (parts.length < 5 || !parts[2].contains("github.com")) {
                return ResponseEntity.badRequest().body("Invalid GitHub URL format!");
            }

            String owner = parts[3];
            String repo = parts[4];

            // Agar branch Link mein di ho (e.g. /tree/dev), toh wo le lo, warna default "main" rakho
            String branch = "main";
            if (parts.length >= 7 && "tree".equals(parts[5])) {
                branch = parts[6];
            }

            String repoName = owner + "/" + repo;

            // STEP 2: Fallback Logic Ke Saath ZIP Download Karo
            byte[] zipBytes = downloadRepoZip(owner, repo, branch);

            if (zipBytes == null) {
                return ResponseEntity.badRequest().body("Failed to download ZIP. Please check repo accessibility and branch name.");
            }

            List<Document> documents = new ArrayList<>();
            System.out.println("Downloaded " + zipBytes.length + " bytes for repo: " + repoName);

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;

                while ((entry = zip.getNextEntry()) != null) {

                    if (entry.isDirectory()) continue;

                    String rawEntryName = entry.getName();
                    String fullPath = "/" + rawEntryName;


                    if (isIgnoredFolder(fullPath)) continue;

                    String path = rawEntryName.substring(rawEntryName.indexOf('/') + 1);
                    if (path.isEmpty() || isIgnoredFile(path)) continue;

                    byte[] eachfiletbytes = zip.readAllBytes();
                    if (eachfiletbytes.length == 0 || eachfiletbytes.length > MAX_FILE_SIZE) continue;

                    if (isBinaryFile(eachfiletbytes)) continue;

                    String rawContent = new String(eachfiletbytes, StandardCharsets.UTF_8);
                    String[] lines = rawContent.split("\r?\n");

                    StringBuilder lineNumberedContent = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) {
                        lineNumberedContent.append(String.format("L%d: %s\n", (i + 1), lines[i]));
                    }

                    int totalLines = lines.length;
                    documents.add(new Document(
                            "File: " + path + " | Total Lines: " + totalLines + "\n\n" + lineNumberedContent.toString(),
                            Map.of("repo", repoName, "path", path, "extension", getExtension(path))
                    ));
                }
            }

            System.out.println("Before Chunking Document size: " + documents.size());

            DocumentSplitter splitter2 = DocumentSplitters.recursive(1200, 120);
            List<Document> repofiles = new ArrayList<>();

            for (Document springAiDoc : documents) {
                dev.langchain4j.data.document.Document tempLc4jDoc =
                        dev.langchain4j.data.document.Document.from(springAiDoc.getText());

                List<dev.langchain4j.data.segment.TextSegment> segments = splitter2.split(tempLc4jDoc);

                for (dev.langchain4j.data.segment.TextSegment segment : segments) {
                    repofiles.add(new Document(segment.text(), springAiDoc.getMetadata()));
                }
            }

            vectorStore.delete("repo == '" + repoName + "'");
            System.out.println("Adding " + repofiles.size() + " Documents in batches into PgVector...");


// Vector DB call
            vectorStore.add(repofiles);
            System.out.println("Added  Documents  into PgVector");



            return ResponseEntity.ok(documents.size() + " files, " + repofiles.size() + " chunks loaded from " + repoName);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed: " + e.getMessage());
        }
    }



//  -----------------------------------------------------------------------
    // Helper: Try 'main' branch first, if fails try 'master'
// -----------------------------------------------------------------------
    private byte[] downloadRepoZip(String owner, String repo, String preferredBranch) {
        List<String> branchesToTry = List.of(preferredBranch, "main", "master");

        for (String branch : branchesToTry) {
            try {
                String zipUrl = String.format("https://codeload.github.com/%s/%s/zip/refs/heads/%s", owner, repo, branch);
                System.out.println("Attempting download from: " + zipUrl);

                byte[] bytes = RestClient.create()
                        .get()
                        .uri(zipUrl)
                        .retrieve()
                        .body(byte[].class);

                if (bytes != null && bytes.length > 0) {

                    return bytes;
                }
            } catch (Exception ignored) {
                // Agar branch 'main' nahi mili toh next branch 'master' try karega
            }
        }
        return null;
    }

// -----------------------------------------------------------------------
// Helper Filters (Class level helpers)
// -----------------------------------------------------------------------

    private boolean isIgnoredFolder(String fullPath) {
        List<String> ignored = List.of(
                "/.git/", "/.github/", "/.idea/", "/.vscode/",
                "/node_modules/", "/target/", "/build/", "/dist/",
                "/out/", "/bin/", "/obj/", "/.mvn/", "/.gradle/",
                "/coverage/", "/vendor/", "/__pycache__/", "/.next/", "/.nuxt/"
        );
        for (String folder : ignored) {
            if (fullPath.contains(folder)) return true;
        }
        return false;
    }

    private boolean isIgnoredFile(String path) {
        String fileName = path.toLowerCase();

        // Lockfiles & Minified JS/CSS ignore karo
        if (fileName.endsWith("package-lock.json") || fileName.endsWith("yarn.lock") ||
                fileName.endsWith("pnpm-lock.yaml") || fileName.endsWith("composer.lock") ||
                fileName.endsWith(".min.js") || fileName.endsWith(".min.css") ||
                fileName.endsWith(".map")) {
            return true;
        }

        // Binary / Media Extensions ignore karo
        String ext = getExtension(fileName);
        Set<String> binaryExts = Set.of(
                "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "pdf", "zip", "tar", "gz",
                "jar", "war", "ear", "class", "exe", "dll", "so", "dylib", "mp3", "mp4",
                "ttf", "woff", "woff2", "eot", "db", "sqlite"
        );

        return binaryExts.contains(ext);
    }

    // Byte Array Mein Null Byte Check (Binary vs Text Detector)
    private boolean isBinaryFile(byte[] bytes) {
        int checkLength = Math.min(bytes.length, 1024); // Shuruati 1KB check karo
        for (int i = 0; i < checkLength; i++) {
            if (bytes[i] == 0) { // Null byte presence = binary file
                return true;
            }
        }
        return false;
    }

    // "UserService.java" -> "java"
    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }


}