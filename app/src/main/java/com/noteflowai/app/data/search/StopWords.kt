package com.noteflowai.app.data.search

/**
 * Common English stop words to exclude from TF-IDF indexing.
 * Reduces noise from very frequent words that carry little semantic weight.
 */
object StopWords {
    private val words: Set<String> = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "could", "should", "may", "might", "shall", "can", "need",
        "dare", "ought", "used", "it", "its", "this", "that", "these",
        "those", "i", "me", "my", "myself", "we", "our", "ours", "ourselves",
        "you", "your", "yours", "yourself", "yourselves", "he", "him", "his",
        "himself", "she", "her", "hers", "herself", "they", "them", "their",
        "theirs", "themselves", "what", "which", "who", "whom", "when",
        "where", "why", "how", "all", "each", "every", "both", "few", "more",
        "most", "other", "some", "such", "no", "nor", "not", "only", "own",
        "same", "so", "than", "too", "very", "s", "t", "just", "don", "now",
        "about", "above", "after", "again", "against", "am", "any", "because",
        "before", "below", "between", "into", "through", "during", "further",
        "here", "there", "then", "once", "also", "well", "back", "even",
        "still", "new", "like", "get", "got", "make", "made", "go", "going",
        "goes", "went", "come", "came", "take", "took", "see", "saw", "know",
        "knew", "think", "thought", "say", "said", "tell", "told", "give",
        "gave", "first", "one", "two", "use", "way", "many", "much", "right",
        "want", "look", "looked", "let", "keep", "try", "tried", "ask",
        "asked", "need", "feel", "felt", "put", "set", "left", "run", "day",
        "time", "year", "people", "man", "woman", "child", "world", "life",
        "hand", "part", "place", "case", "week", "company", "end",
        "may", "however", "something", "being", "since", "long", "great",
        "little", "own", "another", "must", "real", "old", "different"
    )

    fun contains(word: String): Boolean = words.contains(word)
}
