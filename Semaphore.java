public class Semaphore {

    private int value;

    public Semaphore()
    {
        value = 0;
    }

    public Semaphore(int initial)
    {
        if (initial < 0)
        {
            throw new IllegalArgumentException("Invalid value");//ارمي Exception لأن المستخدم أو البرنامج بعت قيمة غير مسموح بيها.
        }
        value = initial;
    }
    //دي بتخلي Thread واحدة بس في اللحظة الواحدة تنفذ الجزء المحمي

    public synchronized void P() throws InterruptedException  //هي بتحدد نوع الـ Exception اللي ممكن يحصل داخل الـ Method كلها
    {
        while (value == 0)
        {
            wait();
        }
        value--;
    }

    public synchronized void V()
    {
        value++;
        notifyAll();  // نبّه كل الـ Threads اللي مستنية على نفس كائن الـ Semaphore.
    }


    public synchronized boolean tryP(long wait_time) throws InterruptedException
    {
        if (wait_time < 0) {
            throw new IllegalArgumentException("Invalid wait_time");
        }

        long end = System.currentTimeMillis() + wait_time;

        while (value == 0)
        {

            long remaining = end - System.currentTimeMillis();

            if (remaining <= 0)
            {
                return false;
            }

            wait(remaining);
        }

        value--;
        return true;
    }

}
