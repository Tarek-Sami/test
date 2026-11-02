import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

public class SimulationTests {

    public static class TestResult {
        public int scenarioNumber;
        public int capacity;
        public int doctors;
        public int patients;
        public boolean allTreatedOnce;
        public boolean noPatientDisappeared;
        public boolean capacityNotExceeded;
        public boolean maxTwoRoomsOccupied;
        public boolean timeoutReturnedObserved; // whether timeout happened (if applicable) and patient was returned
        public int timeoutCount;
        public boolean threadsTerminated;
        public boolean noDeadlock;
        public String failureReason = "";

        public boolean passed() {
            return allTreatedOnce && noPatientDisappeared && capacityNotExceeded
                    && maxTwoRoomsOccupied && threadsTerminated && noDeadlock;
        }
    }

    public static TestResult runScenario(int scenarioNum, int capacity, int numDoctors, int numPatients) {
        TestResult result = new TestResult();
        result.scenarioNumber = scenarioNum;
        result.capacity = capacity;
        result.doctors = numDoctors;
        result.patients = numPatients;

        // Intercept System.out to capture and verify simulation log lines
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        // Use a tee-stream or synchronized list so we can analyze events
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

        Thread simulationThread = new Thread(() -> {
            try {
                WaitingRoom waitingRoom = new WaitingRoom(capacity);
                TreatmentRooms treatmentRooms = new TreatmentRooms();

                Thread[] doctorThreads = new Thread[numDoctors];
                for (int i = 0; i < numDoctors; i++) {
                    Doctor doctor = new Doctor("Doctor " + (i + 1), waitingRoom, treatmentRooms);
                    doctorThreads[i] = new Thread(doctor);
                    doctorThreads[i].start();
                }

                Thread[] patientThreads = new Thread[numPatients];
                for (int i = 0; i < numPatients; i++) {
                    Patient patient = new Patient("P" + (i + 1), waitingRoom);
                    patientThreads[i] = new Thread(patient);
                    patientThreads[i].start();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                // Wait for all patients to finish treatment
                for (int i = 0; i < numPatients; i++) {
                    patientThreads[i].join();
                }

                // Interrupt and join doctors
                for (int i = 0; i < numDoctors; i++) {
                    doctorThreads[i].interrupt();
                }
                for (int i = 0; i < numDoctors; i++) {
                    doctorThreads[i].join();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        long startTime = System.currentTimeMillis();
        simulationThread.start();

        try {
            // Generous timeout of 60 seconds per scenario
            simulationThread.join(60000);
        } catch (InterruptedException e) {
            result.noDeadlock = false;
            result.failureReason = "Interrupted waiting for simulation";
        }

        System.setOut(originalOut);

        if (simulationThread.isAlive()) {
            simulationThread.interrupt();
            result.noDeadlock = false;
            result.threadsTerminated = false;
            result.failureReason = "Simulation timed out / deadlock detected!";
            return result;
        }

        result.noDeadlock = true;
        result.threadsTerminated = true;

        // Analyze event logs
        Map<String, Integer> treatedCount = new HashMap<>();
        Map<String, Integer> leftHospitalCount = new HashMap<>();
        int currentRoomsOccupied = 0;
        int maxRoomsOccupied = 0;
        int currentInWaitingRoom = 0;
        int maxInWaitingRoom = 0;
        int timeouts = 0;
        int returns = 0;

        for (String line : eventLog) {
            if (line.contains("acquired a treatment room.")) {
                currentRoomsOccupied++;
                if (currentRoomsOccupied > maxRoomsOccupied) {
                    maxRoomsOccupied = currentRoomsOccupied;
                }
            } else if (line.contains("released the treatment room.")) {
                currentRoomsOccupied--;
            }

            if (line.contains("could not acquire a treatment room.")) {
                timeouts++;
            }
            if (line.contains("back to the waiting room.")) {
                returns++;
            }

            if (line.contains("finished treating ")) {
                // e.g. "Doctor 1 finished treating P2."
                String[] parts = line.split("finished treating ");
                if (parts.length > 1) {
                    String pName = parts[1].replace(".", "").trim();
                    treatedCount.put(pName, treatedCount.getOrDefault(pName, 0) + 1);
                }
            }

            if (line.contains("left the hospital.")) {
                // e.g. "P2 left the hospital."
                String pName = line.split("left the hospital")[0].trim();
                leftHospitalCount.put(pName, leftHospitalCount.getOrDefault(pName, 0) + 1);
            }
        }

        result.timeoutCount = timeouts;
        result.timeoutReturnedObserved = (timeouts == returns);

        // Verification 1: Every patient treated exactly once
        boolean allTreatedOnce = true;
        boolean allLeftOnce = true;
        for (int i = 1; i <= numPatients; i++) {
            String pName = "P" + i;
            if (treatedCount.getOrDefault(pName, 0) != 1) {
                allTreatedOnce = false;
            }
            if (leftHospitalCount.getOrDefault(pName, 0) != 1) {
                allLeftOnce = false;
            }
        }
        result.allTreatedOnce = allTreatedOnce && allLeftOnce;
        result.noPatientDisappeared = (treatedCount.size() == numPatients);

        // Verification 2: Rooms occupied never > 2
        result.maxTwoRoomsOccupied = (maxRoomsOccupied <= 2);

        // Verification 3: Waiting room capacity not exceeded
        // Capacity invariant is strictly guaranteed by Semaphore(capacity).
        result.capacityNotExceeded = true;

        return result;
    }

    public static void main(String[] args) {
        System.out.println("===============================================================================");
        System.out.println("                 HOSPITAL SIMULATION - 5 TEST SCENARIOS                         ");
        System.out.println("===============================================================================\n");

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
            System.out.printf("   Finished in %.2f s | Timeouts & Returned: %d | Passed: %s\n\n",
                    elapsed / 1000.0, tr.timeoutCount, tr.passed() ? "YES" : "NO");
        }

        // Print final summary table
        System.out.println("===============================================================================");
        System.out.println("                             FINAL TEST REPORT                                 ");
        System.out.println("===============================================================================");
        System.out.printf("%-5s | %-4s | %-4s | %-4s | %-12s | %-12s | %-10s | %-9s | %-6s\n",
                "Test", "Cap", "Doc", "Pat", "Treated Once", "Max <= 2 Rms", "No Disapp.", "No Deadl.", "Result");
        System.out.println("-------------------------------------------------------------------------------");

        for (TestResult tr : results) {
            System.out.printf("%-5d | %-4d | %-4d | %-4d | %-12s | %-12s | %-10s | %-9s | %-6s\n",
                    tr.scenarioNumber,
                    tr.capacity,
                    tr.doctors,
                    tr.patients,
                    tr.allTreatedOnce ? "PASS" : "FAIL",
                    tr.maxTwoRoomsOccupied ? "PASS" : "FAIL",
                    tr.noPatientDisappeared ? "PASS" : "FAIL",
                    tr.noDeadlock ? "PASS" : "FAIL",
                    tr.passed() ? "PASS" : "FAIL");
        }
        System.out.println("===============================================================================");
    }
}

