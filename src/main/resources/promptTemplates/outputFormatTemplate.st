{
  "type": "object",
  "required": ["questions"],
  "additionalProperties": false,
  "properties": {
    "questions": {
      "type": "array",
      "minItems": {total_questions},
      "maxItems": {total_questions},
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "question_text",
          "question_type",
          "difficulty",
          "option_a",
          "option_b",
          "option_c",
          "option_d",
          "correct_answer",
          "explanation",
          "explanation_wrong_a",
          "explanation_wrong_b",
          "explanation_wrong_c",
          "explanation_wrong_d",
          "source_excerpt",
          "source_page_number"
        ],
        "properties": {
          "question_text": { "type": "string", "minLength": 5 },
          "question_type": { "type": "string", "enum": ["reading", "grammar", "vocabulary", "listening"] },
          "difficulty": { "type": "string", "enum": ["easy", "medium", "hard"] },

          "option_a": { "type": "string", "minLength": 1 },
          "option_b": { "type": "string", "minLength": 1 },
          "option_c": { "type": "string", "minLength": 1 },
          "option_d": { "type": "string", "minLength": 1 },

          "correct_answer": { "type": "string", "enum": ["A", "B", "C", "D"] },
          "explanation": { "type": "string", "minLength": 15 },

          "explanation_wrong_a": { "type": ["null", "string"] },
          "explanation_wrong_b": { "type": ["null", "string"] },
          "explanation_wrong_c": { "type": ["null", "string"] },
          "explanation_wrong_d": { "type": ["null", "string"] },

          "source_excerpt": { "type": ["null", "string"] },
          "source_page_number": { "type": ["integer", "null"] }
        }
      }
    }
  }
}