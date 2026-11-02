import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

public class SimulationTests {

    // صالة انتظار مراقبة للتحقق البرمجي الفعلي من أن سعة الصالة لا يتم تجاوزها أبداً
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

    // غرف علاج مراقبة للتحقق من أن عدد الغرف المشغولة في نفس اللحظة لا يتجاوز 2 أبداً
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
        public boolean timeoutReturnedObserved; // يتحقق من أن نفس المريض الذي انتهت مهلته تمت إعادته

        public boolean threadsTerminated;
        public boolean noDeadlock;
        public String failureReason = "";

        public boolean passed() {
            boolean base = allTreatedOnce && noPatientDisappeared && capacityNotExceeded
                    && maxTwoRoomsOccupied && threadsTerminated && noDeadlock;

            // عند حدوث timeout، يجب التأكد من أن كل مريض حدثت له مهلة تم إرجاعه بعينه بنجاح
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

        // التقاط مخرجات الطباعة للتحقق من مطابقة أحداث المرضى بدقة
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
        boolean allThreadsTerminatedCleanly = true;

        try {
            // 1. انتظار انتهاء جميع المرضى من العلاج مع مهلة لمنع التعليق
            for (int i = 0; i < numPatients; i++) {
                patientThreads[i].join(30000); // 30 ثانية لكل مريض
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
            // 2. إيقاف جميع خيوط الأطباء والمرضى إجبارياً حتى في حال الفشل
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

            // 3. انتظار إغلاق خيوط الأطباء
            for (int i = 0; i < numDoctors; i++) {
                if (doctorThreads[i] != null) {
                    try {
                        doctorThreads[i].join(5000);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }

            // 4. انتظار إغلاق خيوط المرضى
            for (int i = 0; i < numPatients; i++) {
                if (patientThreads[i] != null) {
                    try {
                        patientThreads[i].join(2000);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }

            // 5. التحقق الصارم: يجب أن تفشل التجربة إذا ظل أي Thread يعمل
            for (int i = 0; i < numDoctors; i++) {
                if (doctorThreads[i] != null && doctorThreads[i].isAlive()) {
                    allThreadsTerminatedCleanly = false;
                    result.failureReason = "Doctor " + (i + 1) + " thread is still alive after cleanup!";
                }
            }
            for (int i = 0; i < numPatients; i++) {
                if (patientThreads[i] != null && patientThreads[i].isAlive()) {
                    allThreadsTerminatedCleanly = false;
                    result.failureReason = "Patient P" + (i + 1) + " thread is still alive after cleanup!";
                }
            }
        }

        // استعادة مخرج الطباعة الأصلي
        System.setOut(originalOut);

        result.noDeadlock = !deadlockDetected;
        result.threadsTerminated = allThreadsTerminatedCleanly && !deadlockDetected;

        // 6. التحقق الفعلي من سعة صالة الانتظار وغرف العلاج
        result.capacityNotExceeded = !waitingRoom.isCapacityExceeded() && (waitingRoom.getMaxOccupancy() <= capacity);
        result.maxWaitingOccupancy = waitingRoom.getMaxOccupancy();

        result.maxTwoRoomsOccupied = !treatmentRooms.isRoomsExceeded() && (treatmentRooms.getMaxOccupied() <= 2);
        result.maxRoomsOccupied = treatmentRooms.getMaxOccupied();

        // 7. التحقق من أن نفس المريض الذي انتهت مهلته هو الذي تمت إعادته للصالة
        Map<String, String> doctorAssignedPatient = new HashMap<>(); // اسم الطبيب -> المريض الحالي معه
        Map<String, String> doctorPendingReturn = new HashMap<>();   // اسم الطبيب -> المريض المطلوب إعادته
        int timeouts = 0;
        int successfulReturns = 0;
        boolean allSamePatientsReturned = true;

        Map<String, Integer> treatedCount = new HashMap<>();
        Map<String, Integer> leftHospitalCount = new HashMap<>();

        for (String line : eventLog) {
            // تتبع ارتباط الطبيب بالمريض
            if (line.contains(" is assigned to ")) {
                String[] parts = line.split(" is assigned to ");
                String doc = parts[0].trim();
                String pat = parts[1].replace(".", "").trim();
                doctorAssignedPatient.put(doc, pat);
            }

            // تتبع انتهاء مهلة حجز الغرفة (Timeout)
            if (line.contains(" could not acquire a treatment room.")) {
                String doc = line.split(" could not acquire a treatment room.")[0].trim();
                String pat = doctorAssignedPatient.get(doc);
                timeouts++;
                if (pat != null) {
                    doctorPendingReturn.put(doc, pat);
                } else {
                    allSamePatientsReturned = false;
                }
            }

            // تتبع إعادة المريض للصالة والتأكد من أنه نفس المريض
            if (line.contains(" is sending ") && line.contains(" back to the waiting room.")) {
                String doc = line.split(" is sending ")[0].trim();
                String afterSending = line.split(" is sending ")[1];
                String pat = afterSending.split(" back to the waiting room.")[0].trim();

                String expectedPat = doctorPendingReturn.remove(doc);
                if (expectedPat != null && expectedPat.equals(pat)) {
                    successfulReturns++;
                } else {
                    allSamePatientsReturned = false;
                    result.failureReason = "Mismatch: " + doc + " timed out with "
                            + expectedPat + " but returned " + pat;
                }
            }

            // تتبع انتهاء العلاج
            if (line.contains("finished treating ")) {
                String[] parts = line.split("finished treating ");
                if (parts.length > 1) {
                    String pName = parts[1].replace(".", "").trim();
                    treatedCount.put(pName, treatedCount.getOrDefault(pName, 0) + 1);
                }
            }

            // تتبع مغادرة المستشفى
            if (line.contains("left the hospital.")) {
                String pName = line.split("left the hospital")[0].trim();
                leftHospitalCount.put(pName, leftHospitalCount.getOrDefault(pName, 0) + 1);
            }
        }

        if (!doctorPendingReturn.isEmpty()) {
            allSamePatientsReturned = false;
        }

        result.timeoutCount = timeouts;
        result.returnCount = successfulReturns;
        result.timeoutReturnedObserved = (timeouts > 0) && (timeouts == successfulReturns) && allSamePatientsReturned;

        // 8. التحقق من أن كل مريض عولج بالضبط مرة واحدة وغادر المستشفى بالضبط مرة واحدة
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
        result.noPatientDisappeared = (treatedCount.size() == numPatients && leftHospitalCount.size() == numPatients);

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
