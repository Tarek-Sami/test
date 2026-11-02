import java.util.Scanner;

public class Hospital {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // 1. قراءة المدخلات من المستخدم
        System.out.print("Waiting room capacity: ");
        int capacity = scanner.nextInt();

        System.out.print("Number of doctors: ");
        int numDoctors = scanner.nextInt();

        System.out.print("Number of patients: ");
        int numPatients = scanner.nextInt();

        // 2. إنشاء الموارد المشتركة
        WaitingRoom waitingRoom = new WaitingRoom(capacity);
        TreatmentRooms treatmentRooms = new TreatmentRooms();

        // 3. طباعة قائمة المرضى القادمين
        System.out.println("\nPatients arriving:");
        for (int i = 1; i <= numPatients; i++) {
            System.out.print("P" + i + (i == numPatients ? "" : " "));
        }
        System.out.println("\n");

        // 4. إنشاء وتشغيل الـ Threads للأطباء
        Thread[] doctorThreads = new Thread[numDoctors];
        for (int i = 0; i < numDoctors; i++) {
            Doctor doctor = new Doctor("Doctor " + (i + 1), waitingRoom, treatmentRooms);
            doctorThreads[i] = new Thread(doctor);
            doctorThreads[i].start();
        }

        // 5. إنشاء وتشغيل الـ Threads للمرضى
        Thread[] patientThreads = new Thread[numPatients];
        for (int i = 0; i < numPatients; i++) {
            Patient patient = new Patient("P" + (i + 1), waitingRoom);
            patientThreads[i] = new Thread(patient);
            patientThreads[i].start();
            try {
                // فاصل بسيط بين وصول كل مريض لتنسيق المحاكاة
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 6. انتظار انتهاء جميع المرضى من العلاج (join)
        for (int i = 0; i < numPatients; i++) {
            try {
                patientThreads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 7. إيقاف الـ Threads للأطباء بعد انتهاء المحاكاة
        for (int i = 0; i < numDoctors; i++) {
            doctorThreads[i].interrupt();
        }

        // 8. انتظار توقف جميع الأطباء للتأكد من انتهاء البرنامج بشكل نظيف
        for (int i = 0; i < numDoctors; i++) {
            try {
                doctorThreads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.println("\nAll patients have been treated.");
        System.out.println("Simulation completed.");
        scanner.close();
    }
}
