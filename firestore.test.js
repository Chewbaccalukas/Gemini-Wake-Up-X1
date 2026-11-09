const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildValidChatPayload(userId = ALICE_UID, now = new Date()) {
  return {
    userId,
    localChatId: 1,
    title: "Kotlin Assistant",
    wakeWord: "Hey Kotlin",
    systemInstruction: "You are a senior Kotlin engineer.",
    themeColorHex: "#38BDF8",
    iconName: "Sparkles",
    isDefaultWakeChat: true,
    isPersistent: true,
    createdAt: now,
    updatedAt: now,
  };
}

function buildValidMessagePayload(userId = ALICE_UID, now = new Date()) {
  return {
    userId,
    localChatId: 1,
    localMessageId: 10,
    sender: "user",
    content: "Explain coroutines and flows.",
    triggeredByWakeWord: true,
    messageTimestamp: 1700000000000,
    createdAt: now,
    updatedAt: now,
  };
}

function buildValidWakeWordPayload(userId = ALICE_UID, now = new Date()) {
  return {
    userId,
    localWakeWordId: 5,
    phrase: "Hey Kotlin",
    triggerType: "CHAT",
    chatId: 1,
    chatTitle: "Kotlin Assistant",
    isEnabled: true,
    sensitivity: 0.78,
    metadata: "Kotlin Assistant",
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated user: cannot read or write chats, messages, or wake_words", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("chats").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("messages").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("wake_words").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_1").set(buildValidChatPayload())
  );
});

test("Authenticated owner: can create, read, list, update, and delete their own chat, message, and wake_word", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdTime = new Date(Date.now() - 5000);
  const updatedTime = new Date(Date.now() - 1000);

  // 1. ChatSessionDoc lifecycle
  const chatRef = aliceDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_1");
  await assertSucceeds(chatRef.set(buildValidChatPayload(ALICE_UID, createdTime)));
  await assertSucceeds(chatRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chats").where("userId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    chatRef.update({
      title: "Updated Kotlin Chat",
      wakeWord: "Code Master",
      updatedAt: updatedTime,
    })
  );

  // 2. ChatMessageDoc lifecycle
  const msgRef = aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg_10");
  await assertSucceeds(msgRef.set(buildValidMessagePayload(ALICE_UID, createdTime)));
  await assertSucceeds(msgRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("messages").where("userId", "==", ALICE_UID).get()
  );

  // 3. WakeWordDoc lifecycle
  const wakeRef = aliceDb.collection("users").doc(ALICE_UID).collection("wake_words").doc("wake_5");
  await assertSucceeds(wakeRef.set(buildValidWakeWordPayload(ALICE_UID, createdTime)));
  await assertSucceeds(wakeRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("wake_words").where("userId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    wakeRef.update({
      phrase: "Captain Kotlin",
      sensitivity: 0.9,
      isEnabled: false,
      updatedAt: updatedTime,
    })
  );

  await assertSucceeds(wakeRef.delete());
  await assertSucceeds(msgRef.delete());
  await assertSucceeds(chatRef.delete());
});

test("Cross-user isolation: Bob cannot read, list, update, or delete Alice's documents", async () => {
  const createdTime = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_1").set(buildValidChatPayload(ALICE_UID, createdTime));
    await adminDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg_10").set(buildValidMessagePayload(ALICE_UID, createdTime));
    await adminDb.collection("users").doc(ALICE_UID).collection("wake_words").doc("wake_5").set(buildValidWakeWordPayload(ALICE_UID, createdTime));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chats").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg_10").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("wake_words").doc("wake_5").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("wake_words").doc("wake_5").delete());
});

test("Red Team Audit: rejects shadow fields, identity spoofing, and immutable field tampering", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdTime = new Date(Date.now() - 5000);

  // 1. Shadow field injection on create
  const chatRef = aliceDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_shadow");
  await assertFails(
    chatRef.set({
      ...buildValidChatPayload(ALICE_UID, createdTime),
      isAdmin: true,
    })
  );

  // 2. Identity spoofing (setting userId to BOB_UID inside Alice's path)
  await assertFails(
    chatRef.set({
      ...buildValidChatPayload(BOB_UID, createdTime),
    })
  );

  // 3. Immutable createdAt tampering on update
  const validRef = aliceDb.collection("users").doc(ALICE_UID).collection("chats").doc("chat_valid");
  await assertSucceeds(validRef.set(buildValidChatPayload(ALICE_UID, createdTime)));
  await assertFails(
    validRef.update({
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
});
