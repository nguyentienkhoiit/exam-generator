SYSTEM
Return ONLY one valid JSON object matching the schema exactly. No extra text.

TASK
Generate exactly {total_questions} multiple-choice questions about TOPIC.

TOPIC
{topic}

SOURCE TEXT
{context}

CONFIG
- total_questions: {total_questions}
- question_type_distribution: {question_type_distribution_human}
- difficulty_distribution: {difficulty_distribution_human}
- explanation_language: {explanation_language_human}

HARD RULES
1) 4 options: option_a/option_b/option_c/option_d. correct_answer must be "A"|"B"|"C"|"D".
2) Balance correct_answer across A/B/C/D for {total_questions}:
   near-even (diff<=1), shuffle, no letter >40% of {total_questions}, no >2 repeats.
3) source_page_number must always be null.
4) explanation_wrong_a must be null.
5) explanation_wrong_b must be null.
6) explanation_wrong_c must be null.
7) explanation_wrong_d must be null.
8) source_excerpt must be null.

QUESTION_TEXT FORMAT
- reading:
  question_text MUST be exactly 2 lines:
  Line1: Question: <question sentence in English>
  Line2: Passage: <English passage, 70–110 words, copied verbatim from SOURCE TEXT>
- listening:
  question_text MUST be exactly 2 lines:
  Line1: Question: <question sentence in English>
  Line2: Transcript: <English transcript, 70–110 words, copied verbatim from SOURCE TEXT; do NOT invent>
- grammar:
  question_text MUST be exactly 1 line:
  Line1: Question: <grammar question sentence in English>
- vocabulary:
  question_text MUST be exactly 1 line:
  Line1: Question: <vocabulary question sentence in English>

DIFFICULTY (count words in the Question line only; English words)
- easy: <=12 words
- medium: 15–25 words
- hard: >=25 words

EXPLANATION
- explanation must be EXACTLY 1 sentence in {explanation_language_human} explaining ONLY why the correct answer is correct.
- Do NOT mention other options.

FINAL CHECK
- Ensure valid JSON.
- Ensure null fields are literal null (not "null").
- Keep passages/transcripts short to avoid truncation.

OUTPUT SCHEMA
{output_format}