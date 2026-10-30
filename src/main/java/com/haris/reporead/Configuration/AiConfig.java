package com.haris.reporead.Configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean(name = "openaichatmodel")
    public ChatClient openaichatmodel(OpenAiChatModel openAiChatModel, ChatMemory chatMemory) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors( new SimpleLoggerAdvisor() ,
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
    @Bean(name = "genaichatmodel")

    public ChatClient genaichatmodel(GoogleGenAiChatModel chatModel ,ChatMemory chatMemory){

        return ChatClient.builder(chatModel)
                .defaultAdvisors( new SimpleLoggerAdvisor() ,
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
    @Bean
    public ChatMemory chatMemory(
            JdbcChatMemoryRepository repository) {

        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
