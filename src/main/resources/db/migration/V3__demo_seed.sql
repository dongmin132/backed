-- Demo data for the running app: the three seed customers become members of a small, fictional
-- climbing gym, each with a few messages the investigator can read. Tests truncate every table
-- and reload only V2, so they keep the neutral seed names and start with no messages.

update customers set name = 'Maya Lindqvist', email = 'maya.lindqvist@example.com'
 where token = 'demo-a-7f3k9q2m';
update customers set name = 'Daniel Okafor', email = 'daniel.okafor@example.com'
 where token = 'demo-b-4p8w1x6z';
update customers set name = 'Priya Raman', email = 'priya.raman@example.com'
 where token = 'demo-c-9h2n5r3t';

insert into customer_messages (customer_id, body, created_at)
select c.id, m.body, now() - m.age
  from (values
        ('demo-a-7f3k9q2m', 'Is the beginner technique class still on Thursday evenings?', interval '6 days'),
        ('demo-a-7f3k9q2m', 'Could you send $20 of my class pack back to my card? I am away for a month and will top up again when I am back.', interval '1 day'),
        ('demo-b-4p8w1x6z', 'Can a friend climb on my balance, or does she need her own account?', interval '5 days'),
        ('demo-b-4p8w1x6z', 'The auto-belay on the tall wall was out of order all evening, so I left after twenty minutes.', interval '20 hours'),
        ('demo-c-9h2n5r3t', 'Do chalk and shoe rental come out of the same balance?', interval '4 days'),
        ('demo-c-9h2n5r3t', 'My top-up page said Processing for a while. Did it go through? I did not want to pay twice.', interval '3 hours')
       ) as m (token, body, age)
  join customers c on c.token = m.token;
