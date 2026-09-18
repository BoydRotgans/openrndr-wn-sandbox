# Principles

The small set of things the whole evening is made of. Twelve in all: five that decide how something
looks standing still, five that decide how it changes, and two that belong to the room rather than to
a slide. Each is meant to become one class that every drawer asks, so that a change to the system is
one edit rather than thirty.

This is a system, not a uniform. The last section says where a slide is free to break it.

## The one idea underneath

Everything on the wall is a precast component, seen in one of three ways.

- **Catalogue.** The component as a specimen. Ordered, numbered, evenly spaced, named. The opening
  wall, the belt, the gallery, the hundred elements, the sectors.
- **Technical drawing.** The component explained. Plans, sections, leaders, figures, a share picked out
  in red. Every chart, every diagram, the case studies, the hidden story.
- **Sculpture.** The component as a thing with weight. Standing in the round, lit from one side,
  casting a real shadow. The yard, the block city, the recyclage, the shadow walls.

A slide belongs to one of the three and says so. That is the first decision, because it settles the
ground colour, the kind of movement and whether type is a caption or a label. The three share the same
geometry, the same colour roles and the same texture, which is what makes them one family rather than
three styles.

## The ground: five things that decide how it looks

### 1. Colour is a role, never a value

No drawer names a colour. It asks for a role and the system decides what that is on the ground it is
standing on. Five roles carry the whole evening:

| role | what it is |
|---|---|
| paper | the ground the thing stands on |
| ink | the thing itself, when nothing is being said about it |
| accent | the one thing being said right now |
| structure | the supporting mass, the context, the rest of the chart |
| quiet | what is deliberately not being talked about |

Red is the accent and there is one accent idea at a time. Blue is structure. Everything else is paper,
ink or quiet. A drawer that today takes `ink`, `accent`, `blue`, `paper`, `shadow` and `shadowDeep` as
six separate arguments takes one palette instead, and the dark and light grounds become two named
palettes rather than a hand-swapped set of hexes.

**Class: `Palette`**, with `Palette.onBlack`, `Palette.onConcrete` and `Palette.drawing`, and the
shadow pair derived from the ground rather than stated.

### 2. Type is a role too, and the room is in the arithmetic

Five sizes for the whole evening: display, title, label, body, caption. Each a share of the pane
height, as now, but the class also knows the projection, so any size can be asked what it becomes in
the room.

The geometry is fixed: each wall is twelve metres wide and 1920 pixels across, so a pixel is 6.25 mm,
and the audience sits eight to ten metres away. That turns every size argument into arithmetic. It
also settles an argument I got wrong once already: at this distance the smallest type in the show is
roughly three times the comfortable reading threshold, so nothing here is too small to read. Size is a
question of hierarchy, never of legibility.

**Class: `Scale`**, giving a size from a role and a bounds, and able to report centimetres on the wall
and arcminutes at nine metres for checking.

### 3. One frame, one margin

Every drawer states its own margin today, between 0.02 and 0.085 of the pane. One set of regions
instead: the margin, the title line, the body area, the foot, and the four corners for facts. A drawer
asks the frame where things go.

Type stays inside the margin. Pictures may bleed past it. Nothing that has to be read crosses from one
wall to the other.

**Class: `Frame`**, built from the stage's bounds, handing back named rectangles.

### 4. A label never sits on what it names

It stands clear, in its own space, and a hairline grows from the words to the thing, arriving on the
same number the words fade up on. This exists four times over in the code already, written slightly
differently each time. One implementation, used by the charts, the pieces in the round, the map and
the case studies.

Where there is no room for a leader, the label goes in the nearest corner of the frame. It does not go
on top of the drawing.

**Class: `Leader`**.

### 5. Grain is the surface, not a decoration

The concrete is the thing the evening is projected onto, so it is the same everywhere, it is nailed to
the wall rather than carried by what moves over it, and it only ever darkens. A saturated red or blue
takes less of it than a grey does, because at full strength it turns a flat fill to mud. It is
measured against the texture's own bright end so it can never overexpose.

**Class: `Grain`**, holding the sampler, the measurement and the per-surface strength, used both by the
overlay over the whole frame and by any drawer that needs it inside its own shader.

## The moves: five things that decide how it changes

All five are pure functions of the deck's position or the slide's frame count. None of them carries
state, so all of them can be clicked backwards, jumped into, paused and filmed.

### 6. Arrive: take its step

Something new comes in from one unit back, travelling to its place while it fades up, on one number.
A unit is whatever the drawing is made of: a rung's run, a row's height, a column's width. Nothing
appears from nowhere and nothing scales up out of the floor.

Several things arriving together do so in reading order or from the middle outwards, a fraction of the
click apart, each keeping at least a fifth of the click to itself.

**Class: `Arrive`**, with the stagger as its plural form.

### 7. Grow: from the edge it belongs to

A bar grows out of its axis. A box grows down from its own top edge or out from its own left. A ring
draws from its start. Nothing that means a quantity ever grows from its centre, because a thing
growing from the middle reads as a thing appearing rather than as a thing being measured.

**Class: `Grow`**.

### 8. Re-form: the slots persist

When a picture becomes another picture, the things in it keep their identity. A slot travels to its
new place; a slot with no new place leaves; a newcomer stands up only once the travelling is mostly
done, nearest the middle first. Which thing goes to which place is matched by nearness, not by rank,
so the arrangement stays recognisable across the change.

This is the strongest single idea in the show and it currently exists twice, in the crowd and in the
ladder. It should be one class and it should be reached for far more often than it is.

**Class: `Slots`**.

### 9. Swap: text changes by crossing over

Text that changes is gone by a third of the click and the new text is in from two thirds, so the two
are never both on screen. Text that does not change stays put. A title that keeps its opening words
crossfades straight instead, because fading it out and back blanks the heading mid-sentence.

**Class: `Swap`**.

### 10. Count: one at a time, evenly

Anything counted rather than travelled undoes the deck's ease, so the removals or arrivals come at an
even rate instead of crowding into the middle of the click. Instant per item, never a fade, because a
dozen items half-faded reads as dimming rather than as counting.

**Class: `Count`**.

## Two that belong to the room

### 11. Breathe: the move for things that stand for minutes

A chapter card is up for seven minutes and a course wall for half an hour. Neither can loop visibly and
neither can hold still. So they breathe: a very slow, bounded, non-repeating drift, made of two slow
waves whose rates do not divide into each other, so it never lands back where it started and never
drifts away either. The chapter card's light does this now, and the course walls should.

It is deliberately at the edge of noticing. Someone watching sees nothing move; someone glancing back
after a minute finds the wall different. Stillness is allowed to win where the speaker needs it.

**Class: `Breathe`**.

### 12. Handover: one picture passed to the next

Where one slide opens on the frame the last one closed on, the shape is handed across rather than
computed twice, so the cut cannot be seen. This exists and works. It should be used wherever two
slides share a subject, which is more places than it is used today.

**Class: `Mark`**, already written.

## Where a slide is free

The system is there so that the exceptions read as decisions. A slide may:

- **Bring its own colour when the content brings it.** The sectors collage is a playful set of five
  because it is standing for twelve different industries, and that is the point of it.
- **Set a figure as large as it likes.** When a number is the subject, it is the display size and the
  rest of the frame gets out of its way.
- **Break the grid for one thing.** One element crossing the margin, once, is emphasis. Three is a
  mess.
- **Invent a move nothing else uses**, if what it is describing has no analogue in the five. The
  shatter in the recyclage slide is one; the pen drawing an outline on the opening wall is another.
  Both earn it.
- **Be still.** A slide that says one thing and holds does not need a move at all.

What a slide may not do: name a hex, state a margin in pixels, set type by eye, put a label on top of
a drawing, or animate something in a way that contradicts one of the five moves while looking like it.
An exception should be visible as an exception.
