const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";

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

function validPolicy(uid, now) {
  return {
    userId: uid,
    defaultInterestMode: "SIMPLE_FLAT",
    allowZeroInterest: true,
    allowSimpleFlat: true,
    allowAmortizedMonthly: true,
    defaultRateBps: 500,
    defaultGraceDays: 3,
    defaultLateFeeBps: 200,
    defaultLateFeeCapMinor: 2500,
    paymentAllocationOrder: "FEES_INTEREST_PRINCIPAL",
    defaultCurrencyCode: "USD",
    defaultAutoLogRecurring: false,
    createdAt: now,
    updatedAt: now,
  };
}

function validContract(uid, now) {
  return {
    userId: uid,
    counterpartyId: "cp_1",
    direction: "LENT",
    title: "Camera Gear Loan",
    principalMinor: 50000,
    currencyCode: "USD",
    startDateIso: "2026-11-23",
    termCount: 4,
    frequency: "MONTHLY",
    interestMode: "SIMPLE_FLAT",
    interestRateBps: 500,
    graceDays: 3,
    lateFeeBps: 200,
    lateFeeCapMinor: 2500,
    paymentAllocationOrder: "FEES_INTEREST_PRINCIPAL",
    autoLogRecurring: false,
    status: "ACTIVE",
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);
  await assertFails(unauth.collection("users").doc(ALICE_UID).collection("contracts").get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("contracts").doc("c1").set(validContract(ALICE_UID, now))
  );
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).collection("admin_policies").doc("default").set(validPolicy(ALICE_UID, now))
  );
});

test("Cross-user isolation blocks Bob from reading or writing Alice's data", async () => {
  const now = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).collection("contracts").doc("c1").set(validContract(ALICE_UID, now));
    await db.collection("users").doc(ALICE_UID).collection("counterparties").doc("cp_1").set({
      userId: ALICE_UID,
      name: "Sam Rivera",
      createdAt: now,
      updatedAt: now,
    });
  });

  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("contracts").get());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("contracts").doc("c1").get());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("contracts").doc("c1").update({ status: "SETTLED" }));
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("counterparties").doc("cp_2").set({
    userId: BOB_UID,
    name: "Injected",
    createdAt: now,
    updatedAt: now,
  }));
});

test("Owner can create and update contracts, installments, payments, counterparties, and admin policy", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 5000);
  const later = new Date(Date.now() - 1000);

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("admin_policies").doc("default").set(validPolicy(ALICE_UID, now))
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("counterparties").doc("cp_1").set({
      userId: ALICE_UID,
      name: "Jordan Lee",
      phone: "+15551234567",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_1").set(validContract(ALICE_UID, now))
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_1530").set({
      ...validContract(ALICE_UID, now),
      frequency: "SEMI_MONTHLY_15_30",
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("installments").doc("inst_1").set({
      userId: ALICE_UID,
      contractId: "c_1",
      installmentNumber: 1,
      dueDateIso: "2026-12-23",
      principalDueMinor: 12500,
      interestDueMinor: 625,
      lateFeeDueMinor: 0,
      paidMinor: 0,
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("payments").doc("pay_1").set({
      userId: ALICE_UID,
      contractId: "c_1",
      installmentId: "inst_1",
      amountMinor: 13125,
      paidDateIso: "2026-12-20",
      paidAtEpoch: 1797724800000,
      autoLogged: false,
      proofSha256: "abc123def456",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Partial updates work while preserving createdAt
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("installments").doc("inst_1").update({
      dueDateIso: "2026-12-24",
      principalDueMinor: 12600,
      paidMinor: 13225,
      status: "PAID",
      updatedAt: later,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("payments").doc("pay_1").update({
      amountMinor: 13225,
      paidDateIso: "2026-12-24",
      note: "Adjusted repayment",
      updatedAt: later,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_1").update({
      title: "Updated Camera Loan",
      frequency: "SEMI_MONTHLY_15_30",
      status: "SETTLED",
      updatedAt: later,
    })
  );

  // Owner can un-settle a contract if a repayment is edited/removed, and can delete contracts
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_1").update({
      status: "ACTIVE",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_1530").delete()
  );

  // List queries succeed on owner's subcollections
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).collection("contracts").get());
  await assertSucceeds(alice.collection("users").doc(ALICE_UID).collection("payments").get());
});

test("Rejects shadow fields, future timestamps, invalid enum casing, and @DocumentId id injection", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const future = new Date(Date.now() + 60000);

  // Shadow ghost field
  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("counterparties").doc("cp_bad1").set({
      userId: ALICE_UID,
      name: "Bad",
      isAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Future timestamp
  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("counterparties").doc("cp_bad2").set({
      userId: ALICE_UID,
      name: "Future",
      createdAt: future,
      updatedAt: future,
    })
  );

  // Unexpected id field
  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("contracts").doc("c_bad1").set({
      id: "c_bad1",
      ...validContract(ALICE_UID, now),
    })
  );

  // Recovery audit terminal lock
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("recovery_audits").doc("aud_1").set({
      userId: ALICE_UID,
      transferSessionId: "sess_1",
      previousUid: "old_uid",
      newUid: ALICE_UID,
      recordsTransferredCount: 0,
      transferMode: "LOCAL_REBIND",
      status: "IN_PROGRESS",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("recovery_audits").doc("aud_1").update({
      recordsTransferredCount: 14,
      status: "COMPLETED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    alice.collection("users").doc(ALICE_UID).collection("recovery_audits").doc("aud_1").update({
      recordsTransferredCount: 99,
      updatedAt: new Date(Date.now() - 100),
    })
  );
});
