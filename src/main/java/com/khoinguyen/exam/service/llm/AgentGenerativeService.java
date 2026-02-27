package com.khoinguyen.exam.service.llm;

import com.khoinguyen.exam.dto.ExamDraftResponse;

public interface AgentGenerativeService {

    ExamDraftResponse generate(String prompt);
}
