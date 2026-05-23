package com.khoinguyen.exam.service.llm.impl;

import com.khoinguyen.exam.dto.ExamDraftResponse;
import com.khoinguyen.exam.service.llm.AgentGenerativeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgentGenerativeServiceImpl implements AgentGenerativeService {

    private final ChatClient chatClient;

    public AgentGenerativeServiceImpl(
            @Qualifier("openAiChatModel") ChatModel chatModel
    ) {
        // Spring AI 1.1.x: must use builder()
        this.chatClient = ChatClient.builder(chatModel).build();
    }

//    @Override
//    public ExamDraftResponse generate(String prompt) {
//        return chatClient
//                .prompt()
//                .user(prompt)
//                .call()
//                .entity(ExamDraftResponse.class);
//    }

    @Override
    public ExamDraftResponse generate(String system, String user) {
        return chatClient
                .prompt()
                .system(system)
                .user(user)
                .call()
                .entity(ExamDraftResponse.class);
    }
}
