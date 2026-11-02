import java.util.LinkedList;
import java.util.Queue;

public class WaitingRoom {

    private Queue<Patient> queue;

    private Semaphore spaces;
    private Semaphore patients;
    private Semaphore lock;

    public WaitingRoom(int capacity) {

        if (capacity <= 0) {
            throw new IllegalArgumentException("Invalid capacity");
        }

        queue = new LinkedList<>();

        spaces = new Semaphore(capacity);
        patients = new Semaphore(0);
        lock = new Semaphore(1);
    }

    public void addPatient(Patient patient) throws InterruptedException
    {

        spaces.P();

        lock.P();
        try {
            queue.add(patient);
        } finally {
            lock.V();
        }

        patients.V();
    }

    public Patient removePatient() throws InterruptedException
    {

        patients.P();

        lock.P();
        Patient patient;
        try {
            patient = queue.remove();
        } finally {
            lock.V();
        }

        return patient;  // نطلع المريض من الطابور للدكتور يعالجه
    }

    // يتم استدعاؤها بعد حجز غرفة العلاج بنجاح: يغادر المريض صالة الانتظار ويفرغ مكاناً
    public void releaseSpace() {
        spaces.V();
    }

    // يتم استدعاؤها عند فشل حجز غرفة العلاج: يعود المريض إلى الطابور دون حجز مكان جديد
    public void returnPatient(Patient patient) throws InterruptedException {
        lock.P();
        try {
            queue.add(patient);
        } finally {
            lock.V();
        }
        patients.V();
    }

    public int getWaitingCount() throws InterruptedException {

        lock.P(); // استنى لحد ما محدش يكون بيعدّل الـ Queue، وبعدها اقرأ العدد.
        try {
            return queue.size();
        } finally {
            lock.V();
        }
    }
}
