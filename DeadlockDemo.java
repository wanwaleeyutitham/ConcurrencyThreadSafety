import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * เครื่องมือทดลองที่ 3 — ไฟล์นี้ให้มาแล้ว ไม่ต้องแก้
 *
 * ตรวจ Bank.transfer สองอย่าง ทีละเฟส
 *
 *   เฟส A — โปรแกรมค้างหรือไม่  
 *       จงใจให้สองเธรดโอนเงินสวนทางกัน แล้วใช้ ThreadMXBean ของ JVM
 *       ยืนยันว่ามี deadlock จริงหรือไม่ ไม่ใช่แค่เดาจากเวลาที่ใช้
 *
 *       ข้อสังเกตสำคัญ: โปรแกรมที่ deadlock จะไม่ crash ไม่มี exception
 *       มันแค่ค้าง ซึ่งเป็นเหตุผลว่าทำไมบั๊กชนิดนี้ถึงหายากในระบบจริง
 *
 *   เฟส B — การโอนยังเป็นหน่วยเดียวอยู่หรือไม่
 *       รันเฉพาะเมื่อเฟส A ผ่าน มีเธรดผู้ตรวจสอบคอยอ่านยอดรวม
 *       ระหว่างที่การโอนกำลังดำเนินอยู่
 *
 *       เฟสนี้มีไว้ดักทางลัด: การแก้ deadlock ด้วยการถอดล็อกใบในออก
 *       ถ้าวัดยอดรวมตอนงานจบหมดแล้ว จะดูถูกต้องทุกประการ เพราะทุกการถอน
 *       มีการฝากคู่กันเสมอ บัญชีจึงกลับมาบาลานซ์ตอนจบ
 *
 *       แต่ระหว่างที่หักเงินต้นทางไปแล้ว ยังไม่ทันเพิ่มให้ปลายทาง
 *       เงินก้อนนั้นไม่ได้อยู่ที่ไหนเลย ใครอ่านยอดรวมตอนนั้นจะเห็นเงินหาย
 *       ในระบบจริงคือรายงานยอดที่ผิด หรือการตรวจสอบบัญชีที่ไม่ลงตัว
 *
 * วิธีรัน:  java DeadlockDemo
 */
public class DeadlockDemo {

    private static final int TRANSFERS = 200000;
    private static final long TIMEOUT_MS = 8000;
    private static final int START_EACH = 1000000;
    private static final int EXPECTED_TOTAL = START_EACH * 2;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Demo 3: Transfer between two accounts ===");
        System.out.println("Thread-1 transfers A -> B, " + TRANSFERS + " times");
        System.out.println("Thread-2 transfers B -> A, " + TRANSFERS + " times");
        System.out.println();

        boolean survivedPhaseA = phaseA();

        if (survivedPhaseA) {
            phaseB();
        }

        System.exit(0);   // บังคับปิด เผื่อยังมีเธรดค้างอยู่
    }

    // ================================================================
    // เฟส A — ค้างหรือไม่
    // ================================================================

    /** @return true ถ้าไม่ค้าง (ผ่านไปเฟส B ได้) */
    private static boolean phaseA() throws Exception {
        System.out.println("--- Phase A: does it hang? ---");
        System.out.println("Waiting at most " + (TIMEOUT_MS / 1000) + " seconds");

        final Account a = new Account(1, START_EACH);
        final Account b = new Account(2, START_EACH);

        Thread t1 = mover("mover-A-to-B", a, b);
        Thread t2 = mover("mover-B-to-A", b, a);

        // daemon เพื่อให้ JVM ปิดตัวได้แม้เธรดจะค้างจริง ๆ
        t1.setDaemon(true);
        t2.setDaemon(true);

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long start = System.currentTimeMillis();
        t1.start();
        t2.start();

        boolean stuck = false;
        long[] deadlocked = null;

        while (System.currentTimeMillis() - start < TIMEOUT_MS) {
            if (!t1.isAlive() && !t2.isAlive()) {
                break;
            }
            deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                stuck = true;
                break;
            }
            Thread.sleep(50);
        }

        if (!stuck && (t1.isAlive() || t2.isAlive())) {
            stuck = true;   // ไม่จบภายในเวลา ถือว่าค้าง
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Elapsed: " + elapsed + " ms");

        if (!stuck) {
            System.out.println("Result : completed, no hang");
            System.out.println();
            return true;
        }

        System.out.println("Result : STUCK (DEADLOCK)");
        if (deadlocked != null) {
            System.out.println("The JVM confirms " + deadlocked.length
                    + " deadlocked thread(s):");
            for (int i = 0; i < deadlocked.length; i++) {
                System.out.println("   - " + mx.getThreadInfo(deadlocked[i]).getThreadName());
            }
        }
        System.out.println();
        System.out.println("Thread-1 holds A and waits for B.");
        System.out.println("Thread-2 holds B and waits for A.");
        System.out.println("Neither will ever let go.");
        System.out.println();
        System.out.println("Note there is no error, no exception, nothing in red.");
        System.out.println("The program simply stopped.");
        System.out.println();
        System.out.println("Fix TODO 2 in Bank.java and run this file again.");
        System.out.println("(Phase B is skipped while the program still hangs.)");
        return false;
    }

    // ================================================================
    // เฟส B — การโอนยังเป็นหน่วยเดียวอยู่หรือไม่
    // ================================================================

    private static void phaseB() throws Exception {
        System.out.println("--- Phase B: is the transfer still atomic? ---");
        System.out.println("An auditor thread reads the combined total while money moves.");

        final Account a = new Account(1, START_EACH);
        final Account b = new Account(2, START_EACH);

        Thread t1 = mover("mover-A-to-B", a, b);
        Thread t2 = mover("mover-B-to-A", b, a);
        t1.setDaemon(true);
        t2.setDaemon(true);

        // ผู้ตรวจสอบต้องถือล็อกทั้งสองใบตอนอ่าน มิฉะนั้นจะได้ภาพครึ่ง ๆ กลาง ๆ
        // แม้โค้ดจะถูกต้องแล้วก็ตาม (อ่าน a เสร็จ มีการโอนแทรก แล้วค่อยอ่าน b)
        //
        // และต้องเรียงลำดับตาม id เหมือน transfer ที่แก้ถูกแล้ว
        // เพื่อไม่ให้ตัวผู้ตรวจสอบเองกลายเป็นต้นเหตุของ deadlock
        final Account lo = a.id() < b.id() ? a : b;
        final Account hi = a.id() < b.id() ? b : a;

        final long[] samples = new long[]{0, 0};        // {อ่านทั้งหมด, ครั้งที่ไม่ลงตัว}
        final int[] lowest = new int[]{EXPECTED_TOTAL};
        final boolean[] running = new boolean[]{true};

        Thread auditor = new Thread(new Runnable() {
            public void run() {
                while (running[0]) {
                    int total;
                    synchronized (lo) {
                        synchronized (hi) {
                            total = a.balance() + b.balance();
                        }
                    }
                    samples[0]++;
                    if (total != EXPECTED_TOTAL) {
                        samples[1]++;
                        if (total < lowest[0]) {
                            lowest[0] = total;
                        }
                    }
                }
            }
        }, "auditor");
        auditor.setDaemon(true);

        t1.start();
        t2.start();
        auditor.start();

        t1.join(TIMEOUT_MS);
        t2.join(TIMEOUT_MS);
        running[0] = false;
        auditor.join(1000);

        int finalTotal = a.balance() + b.balance();

        System.out.println("Final total  : " + finalTotal
                + "   (should be " + EXPECTED_TOTAL + ")");
        System.out.println("Audit samples: " + samples[0] + " taken, "
                + samples[1] + " did not balance");
        System.out.println();

        if (samples[1] == 0 && finalTotal == EXPECTED_TOTAL) {
            System.out.println("The books balanced in every single sample taken while");
            System.out.println("money was moving.");
            System.out.println();
            System.out.println("Both goals are met: a consistent lock order removed the");
            System.out.println("deadlock, and holding both locks kept the transfer atomic.");
        } else {
            System.out.println("The hang is gone, but the books did not balance while the");
            System.out.println("transfers were running. Lowest total observed: " + lowest[0]);
            System.out.println("That is " + (EXPECTED_TOTAL - lowest[0])
                    + " unit(s) sitting in neither account.");
            System.out.println();
            System.out.println("A transfer has to be all-or-nothing. Between taking money out");
            System.out.println("of one account and putting it into the other, that money is");
            System.out.println("nowhere. Checking the total after everything has finished will");
            System.out.println("not reveal this: every withdrawal did get a matching deposit");
            System.out.println("eventually, so it balances at rest. Only an observer reading");
            System.out.println("DURING the transfer can see the gap.");
            System.out.println();
            System.out.println("Did you fix the deadlock by dropping one of the two locks in");
            System.out.println("transfer? That is not the fix. Put both locks back and change");
            System.out.println("the ORDER they are acquired in instead.");
        }
    }

    private static Thread mover(String name, final Account from, final Account to) {
        return new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < TRANSFERS; i++) {
                    Bank.transfer(from, to, 1);
                }
            }
        }, name);
    }
}
