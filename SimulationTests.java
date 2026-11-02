import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

public class SimulationTests {

    // صالة انتظار مراقبة للتحقق البرمجي الدقيق من أن سعة الصالة لا يتم تجاوزها أبداً
    static class MonitoredWaitingRoom extends WaitingRoom {
        private final int capacity;
        private int currentOccupancy = 0;
        private int maxOccupancy = 0;
        private boolean capacityExceeded = false;

        public MonitoredWaitingRoom(int capacity) {
            super(capacity);
            this.capacity = capacity;
        }

        @Override
        public void addPatient(Patient patient) throws InterruptedException {
            super.addPatient(patient);
            synchronized (this) {
                currentOccupancy++;
                int queueCount = getWaitingCount();
                int peak = Math.max(currentOccupancy, queueCount);
                if (peak > maxOccupancy) {
                    maxOccupancy = peak;
                }
                if (currentOccupancy > capacity || queueCount > capacity) {
                    capacityExceeded = true;
                }
            }
        }

        @Override
        public void returnPatient(Patient patient) throws InterruptedException {
            super.returnPatient(patient);
            synchronized (this) {
                int queueCount = getWaitingCount();
                if (queueCount > maxOccupancy) {
                    maxOccupancy = queueCount;
                }
                if (queueCount > capacity) {
                    capacityExceeded = true;
                }
            }
        }

        @Override
        public void releaseSpace() {
            synchronized (this) {
                currentOccupancy--;
            }
            super.releaseSpace();
        }

        public synchronized boolean isCapacityExceeded() {
            return capacityExceeded;
        }

        public synchronized int getMaxOccupancy() {
            return maxOccupancy;
        }
    }

    // غرف علاج مراقبة للتحقق البرمجي من أن عدد الغرف المشغولة لا يتجاوز 2 أبداً
    static class MonitoredTreatmentRooms extends TreatmentRooms {
        private int currentOccupied = 0;
        private int maxOccupied = 0;
        private boolean roomsExceeded = false;

        @Override
        public boolean acquireRoom() throws InterruptedException {
            boolean acquired = super.acquireRoom();
            if (acquired) {
                synchronized (this) {
                    currentOccupied++;
                    if (currentOccupied > maxOccupied) {
                        maxOccupied = currentOccupied;
                    }
                    if (currentOccupied > 2) {
                        roomsExceeded = true;
                    }
                }
            }
            return acquired;
        }

        @Override
        public void releaseRoom() {
            synchronized (this) {
                currentOccupied--;
            }
            super.releaseRoom();
        }

        public synchronized boolean isRoomsExceeded() {
            return roomsExceeded;
        }

        public synchronized int getMaxOccupied() {
            return maxOccupied;
        }
    }

    public static class TestResult {
        public int scenarioNumber;
        public int capacity;
        public int doctors;
        public int patients;

        public boolean allTreatedOnce;
        public boolean noPatientDisappeared;
        public boolean capacityNotExceeded;
        public int maxWaitingOccupancy;

        public boolean maxTwoRoomsOccupied;
        public int maxRoomsOccupied;

        public int timeoutCount;
        public int returnCount;
        public boolean timeoutReturnedObserved; // يتحقق من أن كل مريض حدث له timeout تمت إعادته بالاسم

        public boolean threadsTerminated;
        public boolean noDeadlock;
        public String failureReason = "";

        public boolean passed() {
            boolean base = allTreatedOnce && noPatientDisappeared && capacityNotExceeded
                    && maxTwoRoomsOccupied && threadsTerminated && noDeadlock;

            // إذا حدثت حالات timeout، يجب التحقق من أن كل مريض تم إرجاعه بالاسم بنجاح
            if (timeoutCount > 0) {
                return base && timeoutReturnedObserved;
            }
            return base;
        }
    }

    public static TestResult runScenario(int scenarioNum, int capacity, int numDoctors, int numPatients) {
        TestResult result = new TestResult();
        result.scenarioNumber = scenarioNum;
        result.capacity = capacity;
        result.doctors = numDoctors;
        result.patients = numPatients;

        // التقاط مخرجات الطباعة لتحليل الأحداث ومطابقة المرضى
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        List<String> eventLog = Collections.synchronizedList(new ArrayList<>());

        PrintStream capturingOut = new PrintStream(outContent) {
            @Override
            public void println(String s) {
                eventLog.add(s);
                super.println(s);
            }

            @Override
            public void print(String s) {
                super.print(s);
            }
        };

        System.setOut(capturingOut);

        MonitoredWaitingRoom waitingRoom = new MonitoredWaitingRoom(capacity);
        MonitoredTreatmentRooms treatmentRooms = new MonitoredTreatmentRooms();

        Thread[] doctorThreads = new Thread[numDoctors];
        for (int i = 0; i < numDoctors; i++) {
            Doctor doctor = new Doctor("Doctor " + (i + 1), waitingRoom, treatmentRooms);
            doctorThreads[i] = new Thread(doctor, "Doctor-" + (i + 1));
            doctorThreads[i].start();
        }

        Thread[] patientThreads = new Thread[numPatients];
        for (int i = 0; i < numPatients; i++) {
            Patient patient = new Patient("P" + (i + 1), waitingRoom);
            patientThreads[i] = new Thread(patient, "Patient-P" + (i + 1));
            patientThreads[i].start();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        boolean deadlockDetected = false;
        boolean doctorsTerminatedCleanly = true;
        boolean patientsTerminatedCleanly = true;

        try {
            // 1. انتظار انتهاء جميع المرضى من العلاج مع مهلة أمان للكشف عن أي deadlock
            for (int i = 0; i < numPatients; i++) {
                // مهلة 30 ثانية لكل مريض
                patientThreads[i].join(30000);
                if (patientThreads[i].isAlive()) {
                    deadlockDetected = true;
                    result.failureReason = "Deadlock: Patient P" + (i + 1) + " thread never finished!";
                    break;
                }
            }
        } catch (InterruptedException e) {
            deadlockDetected = true;
            result.failureReason = "Test runner interrupted unexpectedly";
        } finally {
            // 2. ضمان إيقاف جميع خيوط الأطباء والمرضى حتى في حال حدوث deadlock أو فشل
            for (int i = 0; i < numDoctors; i++) {
                if (doctorThreads[i] != null) {
                    doctorThreads[i].interrupt();
                }
            }
            for (int i = 0; i < numPatients; i++) {
                if (patientThreads[i] != null && patientThreads[i].isAlive()) {
                    patientThreads[i].interrupt();
                }
            }

            // 3. انتظار إغلاق خيوط الأطباء النظيف
            for (int i = 0; i < numDoctors; i++) {
                if (doctorThreads[i] != null) {
                    try {
                        doctorThreads[i].join(5000);
                        if (doctorThreads[i].isAlive()) {
                            doctorsTerminatedCleanly = false;
                            result.failureReason = "Doctor " + (i + 1) + " thread failed to terminate!";
                        }
                    } catch (InterruptedException e) {
                        doctorsTerminatedCleanly = false;
                    }
                }
            }

            // 4. التأكد من توقف جميع خيوط المرضى بالكامل
            for (int i = 0; i < numPatients; i++) {
                if (patientThreads[i] != null) {
                    try {
                        patientThreads[i].join(2000);
                        if (patientThreads[i].isAlive()) {
                            patientsTerminatedCleanly = false;
                        }
                    } catch (InterruptedException e) {
                        patientsTerminatedCleanly = false;
                    }
                }
            }
        }

        // استعادة مخرج الشاشة الأصلي
        System.setOut(originalOut);

        result.noDeadlock = !deadlockDetected;
        result.threadsTerminated = !deadlockDetected && doctorsTerminatedCleanly && patientsTerminatedCleanly;

        // 5. التحقق الحقيقي من سعة صالة الانتظار وغرف العلاج
        result.capacityNotExceeded = !waitingRoom.isCapacityExceeded() && (waitingRoom.getMaxOccupancy() <= capacity);
        result.maxWaitingOccupancy = waitingRoom.getMaxOccupancy();

        result.maxTwoRoomsOccupied = !treatmentRooms.isRoomsExceeded() && (treatmentRooms.getMaxOccupied() <= 2);
        result.maxRoomsOccupied = treatmentRooms.getMaxOccupied();

        // 6. تحليل سجل الأحداث والتحقق من إعادة المرضى بأسمائهم المحددة
        Map<String, String> doctorCurrentPatient = new HashMap<>(); // اسم الطبيب -> اسم المريض الحالي
        List<String> timedOutPatients = new ArrayList<>();          // المرضى الذين حدث لهم timeout
        List<String> returnedPatients = new ArrayList<>();          // المرضى الذين تمت إعادتهم للصالة

        Map<String, Integer> treatedCount = new HashMap<>();
        Map<String, Integer> leftHospitalCount = new HashMap<>();

        for (String line : eventLog) {
            // تتبع تعيين الطبيب للمريض: "Doctor 1 is assigned to P2."
            if (line.contains(" is assigned to ")) {
                String[] parts = line.split(" is assigned to ");
                String doc = parts[0].trim();
                String pat = parts[1].replace(".", "").trim();
                doctorCurrentPatient.put(doc, pat);
            }

            // تتبع انتهاء وقت محاولة حجز الغرفة: "Doctor 1 could not acquire a treatment room."
            if (line.contains(" could not acquire a treatment room.")) {
                String doc = line.split(" could not acquire a treatment room.")[0].trim();
                String pat = doctorCurrentPatient.get(doc);
                if (pat != null) {
                    timedOutPatients.add(pat);
                }
            }

            // تتبع إعادة المريض المحدد إلى الصالة: "Doctor 1 is sending P2 back to the waiting room."
            if (line.contains(" is sending ") && line.contains(" back to the waiting room.")) {
                String afterSending = line.split(" is sending ")[1];
                String pat = afterSending.split(" back to the waiting room.")[0].trim();
                returnedPatients.add(pat);
            }

            // تتبع انتهاء العلاج: "Doctor 1 finished treating P2."
            if (line.contains("finished treating ")) {
                String[] parts = line.split("finished treating ");
                if (parts.length > 1) {
                    String pName = parts[1].replace(".", "").trim();
                    treatedCount.put(pName, treatedCount.getOrDefault(pName, 0) + 1);
                }
            }

            // تتبع مغادرة المستشفى: "P2 left the hospital."
            if (line.contains("left the hospital.")) {
                String pName = line.split("left the hospital")[0].trim();
                leftHospitalCount.put(pName, leftHospitalCount.getOrDefault(pName, 0) + 1);
            }
        }

        result.timeoutCount = timedOutPatients.size();
        result.returnCount = returnedPatients.size();

        // التحقق الدقيق: هل كل مريض حدث له timeout تمت إعادته بالاسم؟
        boolean everyTimedOutReturned = (result.timeoutCount > 0)
                && (result.timeoutCount == result.returnCount)
                && timedOutPatients.equals(returnedPatients);

        result.timeoutReturnedObserved = everyTimedOutReturned;

        // 7. التحقق من أن كل مريض عولج بالضبط مرة واحدة وغادر المستشفى
        boolean allTreatedOnce = true;
        for (int i = 1; i <= numPatients; i++) {
            String pName = "P" + i;
            int treated = treatedCount.getOrDefault(pName, 0);
            int left = leftHospitalCount.getOrDefault(pName, 0);

            if (treated != 1 || left != 1) {
                allTreatedOnce = false;
                result.failureReason = pName + " treated=" + treated + ", left=" + left;
                break;
            }
        }

        result.allTreatedOnce = allTreatedOnce;
        result.noPatientDisappeared = (treatedCount.size() == numPatients);

        return result;
    }

    public static void main(String[] args) {
        System.out.println("=========================================================================================");
        System.out.println("                       HOSPITAL SIMULATION - 5 TEST SCENARIOS                            ");
        System.out.println("=========================================================================================\n");

        int[][] scenarios = {
            {2, 3, 5},   // Scenario 1: cap=2, doc=3, pat=5
            {1, 1, 5},   // Scenario 2: cap=1, doc=1, pat=5
            {5, 5, 10},  // Scenario 3: cap=5, doc=5, pat=10
            {10, 2, 2},  // Scenario 4: cap=10, doc=2, pat=2
            {1, 5, 15}   // Scenario 5: cap=1, doc=5, pat=15
        };

        List<TestResult> results = new ArrayList<>();

        for (int i = 0; i < scenarios.length; i++) {
            int cap = scenarios[i][0];
            int docs = scenarios[i][1];
            int pats = scenarios[i][2];

            System.out.printf("Running Scenario %d: [Capacity = %d, Doctors = %d, Patients = %d]...\n",
                    (i + 1), cap, docs, pats);
            long start = System.currentTimeMillis();
            TestResult tr = runScenario(i + 1, cap, docs, pats);
            long elapsed = System.currentTimeMillis() - start;

            results.add(tr);
            System.out.printf("   Finished in %.2f s | Timeouts: %d, Returns: %d | Max Cap Used: %d/%d | Passed: %s\n\n",
                    elapsed / 1000.0, tr.timeoutCount, tr.returnCount, tr.maxWaitingOccupancy, tr.capacity,
                    tr.passed() ? "YES" : "NO");
        }

        // طباعة جدول التقرير النهائي المفصل
        System.out.println("=========================================================================================");
        System.out.println("                                    FINAL TEST REPORT                                    ");
        System.out.println("=========================================================================================");
        System.out.printf("%-5s | %-4s | %-4s | %-4s | %-12s | %-10s | %-10s | %-12s | %-9s | %-6s\n",
                "Test", "Cap", "Doc", "Pat", "Treated Once", "Cap Valid", "Rooms <= 2", "Timeout Ret.", "No Deadl.", "Result");
        System.out.println("-----------------------------------------------------------------------------------------");

        for (TestResult tr : results) {
            String timeoutStr;
            if (tr.timeoutCount > 0) {
                timeoutStr = tr.timeoutReturnedObserved ? ("PASS (" + tr.timeoutCount + ")") : "FAIL";
            } else {
                timeoutStr = "N/A (0)";
            }

            System.out.printf("%-5d | %-4d | %-4d | %-4d | %-12s | %-10s | %-10s | %-12s | %-9s | %-6s\n",
                    tr.scenarioNumber,
                    tr.capacity,
                    tr.doctors,
                    tr.patients,
                    tr.allTreatedOnce ? "PASS" : "FAIL",
                    tr.capacityNotExceeded ? ("PASS (" + tr.maxWaitingOccupancy + ")") : "FAIL",
                    tr.maxTwoRoomsOccupied ? ("PASS (" + tr.maxRoomsOccupied + ")") : "FAIL",
                    timeoutStr,
                    tr.noDeadlock ? "PASS" : "FAIL",
                    tr.passed() ? "PASS" : "FAIL");
        }
        System.out.println("=========================================================================================");
    }
}
