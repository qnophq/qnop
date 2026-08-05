// SPDX-License-Identifier: AGPL-3.0-only
//
// The demo screenplay (issue #710): which public-domain excerpts become
// reviews, who owns and reviews them, and the anchored discussion that is
// staged on top. Texts are fetched from Project Gutenberg by their ebook id;
// all authors died well over 70 years ago, the texts are public domain.
//
// `para` indices refer to the excerpt paragraphs captured by
// generate-pdfs.mjs (0-based, title block excluded). Every annotation is
// anchored to a paragraph region + text quote — no document-scoped
// ("global") annotations, per the demo requirements.

// Seed ids from testdata/db/seed.sql — stable by design.
const U = (hex) => `a0000000-0000-0000-0000-0000000000${hex}`;
const T = (hex) => `b0000000-0000-0000-0000-0000000000${hex}`;

export const USERS = {
  nora: U('09'), felix: U('0a'), lena: U('0b'), jonas: U('0c'),
  clara: U('0d'), paul: U('0e'), ida: U('0f'), tom: U('10'),
  eva: U('11'), nils: U('12'), anna: U('13'), ben: U('14'),
  marie: U('15'), leo: U('16'), sofia: U('17'), emil: U('18'),
  greta: U('19'), oskar: U('1a'), julia: U('1b'), david: U('1c'),
  member: U('02'),
};

export const TEAMS = {
  alpha: T('01'), beta: T('02'), legal: T('03'), compliance: T('04'),
  finance: T('05'), procurement: T('06'), engineering: T('07'),
};

// state: DRAFT stays untouched; IN_REVIEW is started and every annotation
// resolved; CHANGES_REQUESTED derives from open annotations; FINALIZED is
// resolved and then finalized by the owner.
export const STORIES = [
  {
    slug: 'the-yellow-wallpaper', startMarker: 'It is very seldom that mere ordinary people', gutenbergId: 1952,
    title: 'The Yellow Wallpaper — Charlotte Perkins Gilman',
    owner: 'clara', teams: ['beta'], users: ['marie', 'tom'],
    state: 'CHANGES_REQUESTED', dueInDays: 9,
    annotations: [
      { author: 'marie', para: 1, type: 'CHANGE', priority: 'MEDIUM',
        comment: 'The narrator introduces the house before herself — flipping these two paragraphs would let the reader meet the voice first.',
        replies: [{ author: 'clara', body: 'Interesting — the detachment is deliberate, but let me try the flipped order in the next version.' }] },
      { author: 'tom', para: 4, type: 'QUESTION', priority: 'LOW',
        comment: 'Is the em-dash spacing here intentional? The rest of the excerpt sets dashes closed.',
        replies: [{ author: 'marie', body: 'Good catch, the source text is inconsistent — I would normalize to closed dashes throughout.' }] },
      { author: 'marie', para: 7, type: 'RISK', priority: 'HIGH',
        comment: 'This passage is doing a lot of quiet foreshadowing. If we trim for length as discussed, this paragraph must survive the cut.' },
      { author: 'tom', para: 10, type: 'PROPOSAL', priority: 'MEDIUM',
        comment: 'Proposal: end the excerpt here. It closes on exactly the right note of unease and keeps us under the page budget.',
        resolved: { note: 'Agreed in the sync — the excerpt will end on this paragraph.' } },
    ],
  },
  {
    slug: 'a-christmas-carol', startMarker: 'MARLEY was dead: to begin with', gutenbergId: 46,
    title: 'A Christmas Carol — Charles Dickens',
    owner: 'ben', teams: ['alpha'], users: ['lena', 'sofia'],
    state: 'CHANGES_REQUESTED', dueInDays: 16,
    annotations: [
      { author: 'lena', para: 0, type: 'CHANGE', priority: 'HIGH',
        comment: 'The famous opening line lands mid-paragraph in this setting. Give it its own paragraph — it is the hook of the whole piece.',
        replies: [{ author: 'ben', body: 'Fair. I will break it out in v2.' },
                  { author: 'sofia', body: '+1, and the following sentence then reads much better as a fresh start.' }] },
      { author: 'sofia', para: 5, type: 'QUESTION', priority: 'MEDIUM',
        comment: 'Do we keep the long aside about the door-nail here? It is charming but slows the introduction of Scrooge considerably.' },
      { author: 'lena', para: 8, type: 'PROPOSAL', priority: 'LOW',
        comment: 'Small typographic point: the run of semicolons in this paragraph could become full stops without losing the rhythm.',
        resolved: { note: 'Done — noted for the next pass.' } },
    ],
  },
  {
    slug: 'jekyll-and-hyde', startMarker: 'Mr. Utterson the lawyer was a man of a rugged countenance', gutenbergId: 43,
    title: 'Dr. Jekyll and Mr. Hyde — Robert Louis Stevenson',
    owner: 'marie', teams: ['compliance'], users: ['felix', 'nils'],
    state: 'CHANGES_REQUESTED', dueInDays: 6,
    annotations: [
      { author: 'felix', para: 0, type: 'CHANGE', priority: 'MEDIUM',
        comment: 'Utterson gets three descriptors too many in the first sentence. Two would sharpen the portrait.',
        replies: [{ author: 'marie', body: 'Which one would you drop? I lean towards keeping "cold" and "dreary".' },
                  { author: 'felix', body: 'Drop "embarrassed in discourse" — it is shown two sentences later anyway.' }] },
      { author: 'nils', para: 3, type: 'CONFLICT', priority: 'HIGH',
        comment: 'This paragraph contradicts the earlier claim that Utterson never judges: here he plainly does. Either soften this or cut the earlier claim.' },
      { author: 'felix', para: 6, type: 'QUESTION', priority: 'LOW',
        comment: 'Is "chocolate-brown" the period-correct spelling with the hyphen? Flagging for the style sheet.',
        resolved: { note: 'Checked against the style sheet — hyphen stays.' } },
    ],
  },
  {
    slug: 'a-scandal-in-bohemia', startMarker: 'To Sherlock Holmes she is always', gutenbergId: 1661,
    title: 'A Scandal in Bohemia — Arthur Conan Doyle',
    owner: 'tom', teams: ['beta'], users: ['anna', 'leo'],
    state: 'CHANGES_REQUESTED', dueInDays: 12,
    annotations: [
      { author: 'anna', para: 0, type: 'RISK', priority: 'MEDIUM',
        comment: 'Opening on "the woman" assumes the reader knows the canon. For a standalone excerpt we may need one framing sentence.',
        replies: [{ author: 'tom', body: 'I would rather trust the reader here — the mystery is the invitation.' },
                  { author: 'anna', body: 'Fine by me if we mention it in the teaser copy instead.' }] },
      { author: 'leo', para: 2, type: 'CHANGE', priority: 'LOW',
        comment: 'Two "however"s in three sentences — the second one can simply go.',
        resolved: { note: 'Removed in my working copy, will land in v2.' } },
      { author: 'anna', para: 6, type: 'QUESTION', priority: 'MEDIUM',
        comment: 'The dates in this paragraph matter later. Should we render them in the original long form or modernize?' },
      { author: 'leo', para: 9, type: 'PROPOSAL', priority: 'LOW',
        comment: 'Suggest a pull-quote from this paragraph for the article header — it is the sharpest Holmes line in the excerpt.' },
    ],
  },
  {
    slug: 'heart-of-darkness', startMarker: 'The Nellie, a cruising yawl', gutenbergId: 219,
    title: 'Heart of Darkness — Joseph Conrad',
    owner: 'lena', teams: ['procurement'], users: ['david', 'greta'],
    state: 'CHANGES_REQUESTED', dueInDays: 20,
    annotations: [
      { author: 'david', para: 1, type: 'CHANGE', priority: 'MEDIUM',
        comment: 'The nautical vocabulary piles up quickly here. A light touch of glossing — one clause — would keep lay readers aboard.',
        replies: [{ author: 'lena', body: 'Agreed, I will gloss "offing" and leave the rest.' }] },
      { author: 'greta', para: 4, type: 'RISK', priority: 'HIGH',
        comment: 'If we cut anywhere for length, do not cut here: this paragraph sets the entire frame narrative. Marking it as protected.' },
      { author: 'david', para: 8, type: 'QUESTION', priority: 'LOW',
        comment: 'Comma before "and" in the closing sentence — the excerpt is inconsistent about the serial comma. Which rule do we follow?',
        resolved: { note: 'Style sheet says serial comma — normalized.' } },
    ],
  },
  {
    slug: 'the-time-machine', startMarker: 'The Time Traveller (for so it will be convenient', gutenbergId: 35,
    title: 'The Time Machine — H. G. Wells',
    owner: 'nils', teams: ['engineering'], users: ['ida', 'oskar'],
    state: 'IN_REVIEW', dueInDays: 14,
    annotations: [
      { author: 'ida', para: 0, type: 'CHANGE', priority: 'MEDIUM',
        comment: 'The parenthetical "(for so it will be convenient to speak of him)" stalls the very first sentence — move it to sentence two.',
        replies: [{ author: 'nils', body: 'Moved. It reads noticeably smoother now.' }],
        resolved: { note: 'Applied — thanks for the sharp eye.' } },
      { author: 'oskar', para: 3, type: 'QUESTION', priority: 'LOW',
        comment: 'Should the geometry digression stay in full? It is the point of the scene, so I assume yes, but flagging the length.',
        resolved: { note: 'Stays in full — it earns its length.' } },
      { author: 'ida', para: 6, type: 'PROPOSAL', priority: 'LOW',
        comment: 'This exchange would make a great epigraph for the print edition.',
        resolved: { note: 'Lovely idea, forwarded to layout.' } },
    ],
  },
  {
    slug: 'alice-in-wonderland', startMarker: 'Alice was beginning to get very tired', gutenbergId: 11,
    title: "Alice's Adventures in Wonderland — Lewis Carroll",
    owner: 'sofia', teams: ['alpha'], users: ['julia', 'emil'],
    state: 'IN_REVIEW', dueInDays: 25,
    annotations: [
      { author: 'julia', para: 0, type: 'CHANGE', priority: 'LOW',
        comment: 'The double "and" chain in the first sentence is faithful to the original but hard on modern ears — worth one gentle comma.',
        resolved: { note: 'One comma added, cadence preserved.' } },
      { author: 'emil', para: 2, type: 'QUESTION', priority: 'MEDIUM',
        comment: 'Do we keep the italics on "very" throughout? They carry Carroll\'s voice, so I vote yes.',
        replies: [{ author: 'sofia', body: 'Yes — the italics stay, they are half the humour.' }],
        resolved: { note: 'Italics confirmed as canonical.' } },
    ],
  },
  {
    slug: 'the-picture-of-dorian-gray', startMarker: 'The studio was filled with the rich odour of roses', gutenbergId: 174,
    title: 'The Picture of Dorian Gray — Oscar Wilde',
    owner: 'leo', teams: ['finance'], users: ['clara', 'ben'],
    state: 'IN_REVIEW', dueInDays: 18,
    annotations: [
      { author: 'clara', para: 1, type: 'CHANGE', priority: 'MEDIUM',
        comment: 'Three scent images in one sentence — rose, lilac, thorn — is one more than the sentence can carry. Cut the lilac?',
        replies: [{ author: 'leo', body: 'Cutting the lilac physically hurts, but you are right.' }],
        resolved: { note: 'The lilac is gone. Wilde forgive us.' } },
      { author: 'ben', para: 4, type: 'QUESTION', priority: 'LOW',
        comment: 'Is the long dialogue run here kept verbatim? A short stage-setting sentence in the middle would help the eye.',
        resolved: { note: 'Verbatim — the run is the rhythm. Noted in the style sheet.' } },
      { author: 'clara', para: 7, type: 'PROPOSAL', priority: 'LOW',
        comment: 'Epigraph candidate: the closing aphorism of this paragraph. It is the thesis of the whole book in one line.',
        resolved: { note: 'Shortlisted for the epigraph.' } },
    ],
  },
  {
    slug: 'dracula', startMarker: 'Left Munich at 8:35', gutenbergId: 345,
    title: 'Dracula — Bram Stoker',
    owner: 'anna', teams: ['legal'], users: ['paul', 'marie'],
    state: 'CHANGES_REQUESTED', dueInDays: 2,
    annotations: [
      { author: 'paul', para: 0, type: 'CHANGE', priority: 'HIGH',
        comment: 'The journal-entry dateline format differs from the one we standardized on for the series. Please align before v2.',
        replies: [{ author: 'anna', body: 'Will do — switching to the day-month-year long form.' }] },
      { author: 'marie', para: 3, type: 'QUESTION', priority: 'MEDIUM',
        comment: 'The travel details are dense here. Do we keep every place name, or only those that recur later in the story?' },
      { author: 'paul', para: 6, type: 'RISK', priority: 'MEDIUM',
        comment: 'The food footnote reads like a digression, but it is the first hint of the memorandum style — cutting it would flatten the voice.',
        resolved: { note: 'Kept, and protected from future trims.' } },
    ],
  },
  {
    slug: 'moby-dick', startMarker: 'Call me Ishmael', gutenbergId: 2701,
    title: 'Moby-Dick — Herman Melville',
    owner: 'david', teams: ['beta'], users: ['tom', 'nora'],
    state: 'CHANGES_REQUESTED', dueInDays: 11,
    annotations: [
      { author: 'nora', para: 0, type: 'PROPOSAL', priority: 'MEDIUM',
        comment: 'Start the excerpt one sentence later and let "Call me Ishmael." stand utterly alone as its own paragraph. It has earned it.',
        replies: [{ author: 'david', body: 'It already is its own sentence — but yes, its own paragraph is stronger. Trying it.' },
                  { author: 'tom', body: 'Strong agree. Nothing should share a line with that sentence.' }] },
      { author: 'tom', para: 3, type: 'CHANGE', priority: 'LOW',
        comment: 'The "hypos" aside needs either a one-word gloss or a footnote — most readers will stumble there.' },
    ],
  },
  {
    slug: 'a-modest-proposal', startMarker: 'It is a melancholy object to those', gutenbergId: 1080,
    title: 'A Modest Proposal — Jonathan Swift',
    owner: 'greta', teams: ['compliance'], users: ['eva', 'felix'],
    state: 'FINALIZED', dueInDays: 4,
    annotations: [
      { author: 'eva', para: 1, type: 'RISK', priority: 'HIGH',
        comment: 'Running the satire without any editorial framing is bold. I recommend one italic line up front so the irony cannot be misread as policy.',
        replies: [{ author: 'greta', body: 'Framing line added to the layout draft — good call for a general audience.' }],
        resolved: { note: 'Framing note agreed and added; risk addressed.' } },
      { author: 'felix', para: 5, type: 'CHANGE', priority: 'LOW',
        comment: 'Modernize the long-s style spellings in this paragraph? The rest of the excerpt already is modernized.',
        resolved: { note: 'Normalized to modern spelling for consistency.' } },
    ],
  },
  {
    slug: 'a-tale-of-two-cities', startMarker: 'It was the best of times', gutenbergId: 98,
    title: 'A Tale of Two Cities — Charles Dickens',
    owner: 'ida', teams: ['alpha'], users: ['nils', 'julia'],
    state: 'FINALIZED', dueInDays: 7,
    annotations: [
      { author: 'julia', para: 0, type: 'PROPOSAL', priority: 'MEDIUM',
        comment: 'The antitheses run for the whole opening paragraph. Typeset it as a single unbroken block — resisting the urge to split makes it land harder.',
        replies: [{ author: 'ida', body: 'Agreed, one block. It is a drumroll, not a list.' }],
        resolved: { note: 'Typeset as one block, exactly as proposed.' } },
      { author: 'nils', para: 4, type: 'QUESTION', priority: 'LOW',
        comment: 'Keep the throne-and-fair-face passage in the excerpt, or does it date the text too specifically for our teaser?',
        resolved: { note: 'Kept — the specificity is the charm.' } },
    ],
  },
  {
    slug: 'frankenstein', startMarker: 'You will rejoice to hear', gutenbergId: 84,
    title: 'Frankenstein — Mary Shelley',
    owner: 'oskar', teams: [], users: ['emil'],
    state: 'DRAFT', dueInDays: 30,
    annotations: [],
  },
];
