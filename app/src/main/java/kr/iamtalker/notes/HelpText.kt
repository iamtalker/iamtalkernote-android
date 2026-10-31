package kr.iamtalker.notes

/** 설정 → 도움말에서 보여 주는 글(컴퓨터 앱의 도움말 → 검색 문법·문법 해설과 같은 내용) */
object HelpText {
    private const val SEARCH_KO = "공백 = AND\n회의록 AND (예산 OR 일정) NOT 취소\n\"정확한 구절\"\n" +
        "& | ! 기호도 AND OR NOT 으로 쓸 수 있습니다(대문자만 연산자로 인식).\n\n" +
        "기간: date:20101011-20240104 만화&아이유  → 그 기간에 고친 글 중 만화와 아이유가 함께 든 글\n" +
        "date:20240101(그 날) · date:202401(그 달) · date:2024(그 해) · date:20240101-(그날부터) · date:-20240104(그날까지)\n" +
        "날짜:·기간: 으로 써도 됩니다.\n\n" +
        "태그는 본문의 #태그, 또는 '태그: a, b' 줄에서 모읍니다."

    private const val MARKDOWN_KO = "제목\n# 제목 1  ·  ## 제목 2  ·  ### 제목 3 …\n\n" +
        "글자\n**굵게**  ·  *기울임*  ·  ~~취소선~~  ·  `코드`\n\n" +
        "목록·인용\n- 항목  ·  1. 번호  ·  - [ ] 할 일  ·  > 인용\n들여쓰기 4칸 = 하위 항목\n\n" +
        "넣기\n[글](주소)  ·  [[노트 제목]](노트 링크)  ·  ![글](그림.png)  ·  <https://…>\n" +
        "표: | 제목1 | 제목2 |  ·  가로줄: ---  ·  코드 블록: ``` … ```\n\n" +
        "확장\n수식 \$a^2\$  ·  접기 <details>  ·  목차 [toc]  ·  각주 [^1]\n태그: #태그"

    fun search(): String = tr(SEARCH_KO)
    fun markdown(): String = tr(MARKDOWN_KO)
}
